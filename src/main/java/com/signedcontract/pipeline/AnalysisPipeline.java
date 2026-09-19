package com.signedcontract.pipeline;

import com.signedcontract.clause.ClauseCoordinateMatcher;
import com.signedcontract.clause.SignatureDetector;
import com.signedcontract.client.AiClient;
import com.signedcontract.client.ImageClient;
import com.signedcontract.client.OcrClient;
import com.signedcontract.diff.DiffEngine;
import com.signedcontract.model.*;
import com.signedcontract.ocr.LanguageDetector;
import com.signedcontract.processor.DocxProcessor;
import com.signedcontract.processor.PDFProcessor;
import com.signedcontract.ratelimit.RateLimiter;
import com.signedcontract.report.ReportAssembler;
import com.signedcontract.warning.WarningCollector;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the full 12-step analysis pipeline described in
 * {@code design.md} (PDF → images → language detection → OCR → clause
 * mapping → clause-image cropping/stitching → signature detection →
 * DOCX parsing → parallel diff/AI comparison → report assembly).
 *
 * <p>Validates: Requirements 14.1–14.3, 21.1–21.4, 23.3, 23.4</p>
 */
public class AnalysisPipeline {

    private final PipelineConfig config;
    private final WarningCollector warnings;

    private final PDFProcessor pdfProcessor;
    private final DocxProcessor docxProcessor;
    private final OcrClient ocrClient;
    private final ImageClient imageClient;
    private final AiClient aiClient;
    private final LanguageDetector languageDetector;
    private final ClauseCoordinateMatcher clauseCoordinateMatcher;
    private final SignatureDetector signatureDetector;
    private final DiffEngine diffEngine;
    private final ReportAssembler reportAssembler;

    public AnalysisPipeline(PipelineConfig config, WarningCollector warnings) {
        this.config = config;
        this.warnings = warnings;

        this.pdfProcessor = new PDFProcessor(config.maxFileSizeMb());
        this.docxProcessor = new DocxProcessor(config.maxFileSizeMb());
        this.ocrClient = new OcrClient(config.pythonServiceUrl(), config.pythonServiceApiKey(), config.ocrAdapter());
        this.imageClient = new ImageClient(config.pythonServiceUrl(), config.pythonServiceApiKey());
        this.aiClient = new AiClient(config.pythonServiceUrl(), config.pythonServiceApiKey());
        this.languageDetector = new LanguageDetector(ocrClient);
        this.clauseCoordinateMatcher = new ClauseCoordinateMatcher(warnings);
        this.signatureDetector = new SignatureDetector(imageClient, warnings);
        this.diffEngine = new DiffEngine();
        this.reportAssembler = new ReportAssembler();
    }

    public PipelineResult execute(String pdfPath, String docxPath) throws ContractAnalysisException {
        // 1. PDF → page images
        List<PageImage> pageImages = pdfProcessor.convertToPageImages(pdfPath);

        // 2. Language / column detection (must complete before OCR, Req 20.1)
        LanguageAnalysis languageAnalysis = languageDetector.detect(
                pageImages, config.primaryLanguage(), warnings);

        // 3. Page-level OCR, rate-limited
        List<PageOcrResult> ocrResults = performOcrForAllPages(pageImages, languageAnalysis);

        // 4. Clause boundary detection + coordinate mapping
        ClauseCoordinateMatcher.MatchResult matchResult =
                clauseCoordinateMatcher.mapFull(ocrResults, languageAnalysis);

        // 5–7. Clause-image crop (+ stitch for multi-page clauses)
        Map<String, String> clauseImages = buildClauseImages(pageImages, matchResult.clauses());

        // 8. Signature detection
        List<Signer> signers = signatureDetector.detect(pageImages, ocrResults);

        // 9. DOCX parsing
        OriginalContract originalContract = docxProcessor.parse(docxPath);

        // Build the signed-contract clause map (content + image)
        Map<String, ClauseContent> signedClauseMap = new LinkedHashMap<>();
        Map<String, String> signedMarkdownForDiff = new LinkedHashMap<>();
        for (ClauseMapping cm : matchResult.clauses()) {
            String image = clauseImages.get(cm.clauseNumber());
            signedClauseMap.put(cm.clauseNumber(), new ClauseContent(cm.markdownContent(), image));
            signedMarkdownForDiff.put(cm.clauseNumber(), cm.markdownContent());
        }

        String title = originalContract.title() != null && !originalContract.title().isBlank()
                ? originalContract.title() : deriveTitleFromClauses(matchResult.clauses());

        SignedContract signedContract = new SignedContract(
                title, signedClauseMap, signers, matchResult.additional(), languageAnalysis);

        // 10. Parallel diff (java-diff-utils) + AI semantic comparison
        Map<String, String> originalMarkdown = new LinkedHashMap<>();
        for (Map.Entry<String, OriginalClauseContent> e : originalContract.clauses().entrySet()) {
            originalMarkdown.put(e.getKey(), e.getValue().content());
        }

        CompletableFuture<Map<String, DiffResult>> diffFuture = CompletableFuture.supplyAsync(() ->
                diffEngine.diffAll(signedMarkdownForDiff, originalMarkdown, config.ocrMaxConcurrency()));

        CompletableFuture<Map<String, AiCompareResult>> aiFuture = performAiComparisons(
                signedMarkdownForDiff, originalMarkdown, languageAnalysis);

        Map<String, DiffResult> diffs = diffFuture.join();
        Map<String, AiCompareResult> aiResults = aiFuture.join();

        // 11. Assemble result
        PipelineResult result = reportAssembler.assemble(
                signedContract, originalContract, diffs, aiResults,
                languageAnalysis, signers, warnings.getWarnings());

        // 12. Retention cleanup — Base64 content lives in-memory in the result;
        // no on-disk temp files are created by this Java-side pipeline, so
        // there is nothing further to delete here (Req 21.2, 21.3).

        return result;
    }

    // ── Step 3: OCR ──────────────────────────────────────────────────────────

    private List<PageOcrResult> performOcrForAllPages(List<PageImage> pageImages, LanguageAnalysis languageAnalysis)
            throws ContractAnalysisException {
        RateLimiter limiter = new RateLimiter(Math.max(1, config.ocrMaxConcurrency()));
        String lang = languageAnalysis.activeLanguage();
        List<CompletableFuture<PageOcrResult>> futures = new ArrayList<>();
        for (PageImage page : pageImages) {
            futures.add(limiter.submit(() -> {
                try {
                    return ocrClient.performOcr(page, lang);
                } catch (ContractAnalysisException e) {
                    warnings.addWarning("OcrClient", Severity.WARN,
                            "Sayfa " + page.pageNumber() + " için OCR başarısız: " + e.getMessage());
                    return new PageOcrResult(page.pageNumber(), List.of(), page.widthPx(), page.heightPx());
                }
            }));
        }
        try {
            List<PageOcrResult> results = new ArrayList<>();
            for (CompletableFuture<PageOcrResult> f : futures) {
                results.add(f.join());
            }
            results.sort(Comparator.comparingInt(PageOcrResult::pageNumber));
            return results;
        } finally {
            limiter.shutdown();
        }
    }

    // ── Step 5–7: crop + stitch ──────────────────────────────────────────────

    private Map<String, String> buildClauseImages(List<PageImage> pageImages, List<ClauseMapping> clauses)
            throws ContractAnalysisException {
        Map<Integer, PageImage> byPage = new HashMap<>();
        for (PageImage pi : pageImages) byPage.put(pi.pageNumber(), pi);

        Map<String, String> images = new LinkedHashMap<>();
        for (ClauseMapping cm : clauses) {
            List<String> croppedPerPage = new ArrayList<>();
            for (int i = 0; i < cm.pageNumbers().size(); i++) {
                int pageNum = cm.pageNumbers().get(i);
                BoundingBox bbox = cm.bboxPerPage().get(i);
                PageImage page = byPage.get(pageNum);
                if (page == null || bbox.width() <= 0 || bbox.height() <= 0) continue;

                String base64Page;
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(page.image(), "jpg", bos);
                    base64Page = Base64.getEncoder().encodeToString(bos.toByteArray());
                } catch (Exception e) {
                    warnings.addWarning("AnalysisPipeline", Severity.WARN,
                            "Sayfa " + pageNum + " kodlanamadı, madde " + cm.clauseNumber() + " görüntüsüz bırakıldı.");
                    continue;
                }

                try {
                    String cropped = imageClient.crop(base64Page, bbox, config.bboxMargin(), 2000, 0.85);
                    croppedPerPage.add(cropped);
                } catch (ContractAnalysisException e) {
                    warnings.addWarning("ImageClient", Severity.WARN,
                            "Madde " + cm.clauseNumber() + " görüntüsü kırpılamadı (sayfa " + pageNum + "): " + e.getMessage());
                }
            }

            if (croppedPerPage.isEmpty()) {
                continue;
            }
            if (croppedPerPage.size() == 1) {
                images.put(cm.clauseNumber(), croppedPerPage.get(0));
            } else {
                try {
                    images.put(cm.clauseNumber(), imageClient.stitch(croppedPerPage, 2));
                } catch (ContractAnalysisException e) {
                    warnings.addWarning("ImageClient", Severity.WARN,
                            "Madde " + cm.clauseNumber() + " için çok sayfalı görüntü birleştirilemedi: " + e.getMessage());
                    images.put(cm.clauseNumber(), croppedPerPage.get(0));
                }
            }
        }
        return images;
    }

    // ── Step 10: AI semantic comparison ─────────────────────────────────────

    private CompletableFuture<Map<String, AiCompareResult>> performAiComparisons(
            Map<String, String> signedMarkdown, Map<String, String> originalMarkdown,
            LanguageAnalysis languageAnalysis) {

        RateLimiter limiter = new RateLimiter(Math.max(1, config.aiMaxConcurrency()));
        LinkedHashSet<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(signedMarkdown.keySet());
        allKeys.addAll(originalMarkdown.keySet());

        Map<String, CompletableFuture<AiCompareResult>> futures = new LinkedHashMap<>();
        for (String key : allKeys) {
            futures.put(key, limiter.submit(() -> {
                try {
                    return aiClient.compare(
                            signedMarkdown.getOrDefault(key, ""),
                            originalMarkdown.getOrDefault(key, ""),
                            languageAnalysis);
                } catch (ContractAnalysisException e) {
                    warnings.addWarning("AiClient", Severity.WARN,
                            "Madde " + key + " için AI karşılaştırması başarısız: " + e.getMessage());
                    return new AiCompareResult(null, "AI service error: " + e.getMessage());
                }
            }));
        }

        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, AiCompareResult> results = new LinkedHashMap<>();
                    futures.forEach((k, f) -> results.put(k, f.join()));
                    limiter.shutdown();
                    return results;
                });
    }

    private String deriveTitleFromClauses(List<ClauseMapping> clauses) {
        return "";
    }
}
