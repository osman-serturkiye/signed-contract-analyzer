package com.signedcontract.clause;

import com.signedcontract.model.*;
import com.signedcontract.warning.WarningCollector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects clause boundaries within page-level OCR output and assigns every
 * OCR block to at most one clause (Property 4), producing per-clause
 * {@link ClauseMapping} entries with aggregated bbox-per-page and Markdown
 * content.
 *
 * <p>Validates: Requirements 5.1–5.6, 20.7, 29.2</p>
 */
public class ClauseCoordinateMatcher {

    private static final Pattern CLAUSE_NUMBER_PATTERN =
            Pattern.compile("^(\\d+(\\.\\d+)*\\.?)\\s");
    private static final Pattern MADDE_PATTERN =
            Pattern.compile("^(MADDE|Madde)\\s+(\\d+(\\.\\d+)*)");
    private static final Pattern ADDITIONAL_PATTERN =
            Pattern.compile("^(EK\\s*PROTOKOL|EK\\s*-?\\s*\\d+|EK\\b|ADDENDUM|APPENDIX|" +
                            "Ek\\s*Protokol|Ek\\s*-?\\s*\\d+|Ek\\b|Addendum|Appendix)",
                    Pattern.CASE_INSENSITIVE);

    private final WarningCollector warnings;

    public ClauseCoordinateMatcher(WarningCollector warnings) {
        this.warnings = warnings;
    }

    /**
     * Maps OCR blocks across all pages to clauses.
     *
     * @param ocrResults        page-level OCR results, in page order
     * @param languageAnalysis  column layout; when {@code multilingual()} is true only
     *                          blocks whose center falls inside the active-language
     *                          column are considered (Req 20.7)
     */
    /** Result of a full mapping pass: clauses plus the `additional` sections. */
    public record MatchResult(List<ClauseMapping> clauses, List<AdditionalSection> additional) {}

    public List<ClauseMapping> map(List<PageOcrResult> ocrResults, LanguageAnalysis languageAnalysis) {
        return mapFull(ocrResults, languageAnalysis).clauses();
    }

    public MatchResult mapFull(List<PageOcrResult> ocrResults, LanguageAnalysis languageAnalysis) {
        LinkedHashMap<String, List<OcrBlock>> clauseBlocks = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<Integer>> clausePages = new LinkedHashMap<>();
        LinkedHashMap<String, Map<Integer, int[]>> clauseBboxPerPage = new LinkedHashMap<>(); // pageNum -> [x1,y1,x2,y2]

        ColumnBBox activeColumn = resolveActiveColumn(languageAnalysis);

        String currentClauseKey = null;
        boolean inAdditional = false;
        String additionalType = null;
        StringBuilder additionalBuffer = new StringBuilder();
        List<AdditionalSection> additionalSections = new ArrayList<>();
        Map<String, Integer> duplicateCount = new HashMap<>();

        for (PageOcrResult page : ocrResults) {
            for (OcrBlock block : page.blocks()) {
                if (activeColumn != null && !blockInColumn(block, activeColumn)) {
                    continue; // Req 20.7: exclude other-language columns
                }

                String trimmed = block.content() == null ? "" : block.content().trim();

                if (isAdditionalSectionStart(trimmed)) {
                    if (inAdditional && additionalBuffer.length() > 0) {
                        additionalSections.add(new AdditionalSection(additionalType, additionalBuffer.toString().strip()));
                    }
                    inAdditional = true;
                    additionalType = resolveAdditionalType(trimmed);
                    additionalBuffer = new StringBuilder();
                    additionalBuffer.append(block.content()).append("\n\n");
                    currentClauseKey = null;
                    continue; // additional/appendix content is not part of `clauses`
                }
                if (inAdditional) {
                    if (block.content() != null && !block.content().isBlank()) {
                        additionalBuffer.append(block.content()).append("\n\n");
                    }
                    continue;
                }

                String clauseNum = extractClauseNumber(trimmed);
                if (clauseNum != null) {
                    currentClauseKey = resolveDuplicateKey(clauseNum, duplicateCount);
                    clauseBlocks.putIfAbsent(currentClauseKey, new ArrayList<>());
                    clausePages.putIfAbsent(currentClauseKey, new LinkedHashSet<>());
                    clauseBboxPerPage.putIfAbsent(currentClauseKey, new LinkedHashMap<>());
                }

                if (currentClauseKey == null) {
                    continue; // preamble before first clause
                }

                clauseBlocks.get(currentClauseKey).add(block);
                clausePages.get(currentClauseKey).add(page.pageNumber());
                expandBbox(clauseBboxPerPage.get(currentClauseKey), page.pageNumber(), block.bbox());
            }
        }

        if (inAdditional && additionalBuffer.length() > 0) {
            additionalSections.add(new AdditionalSection(additionalType, additionalBuffer.toString().strip()));
        }

        List<ClauseMapping> mappings = new ArrayList<>();
        for (String key : clauseBlocks.keySet()) {
            List<OcrBlock> blocks = clauseBlocks.get(key);
            List<Integer> pages = new ArrayList<>(clausePages.get(key));
            List<BoundingBox> bboxes = new ArrayList<>();
            for (Integer p : pages) {
                int[] b = clauseBboxPerPage.get(key).get(p);
                bboxes.add(new BoundingBox(b[0], b[1], b[2] - b[0], b[3] - b[1]));
            }

            if (blocks.isEmpty()) {
                if (warnings != null) {
                    warnings.addWarning("ClauseCoordinateMatcher", Severity.WARN,
                            "Madde " + key + " için OCR bloğu bulunamadı; içerik boş bırakıldı.");
                }
            }

            StringBuilder markdown = new StringBuilder();
            for (OcrBlock b : blocks) {
                if (b.content() != null && !b.content().isBlank()) {
                    markdown.append(b.content()).append("\n\n");
                }
            }

            mappings.add(new ClauseMapping(key, pages, bboxes, blocks, markdown.toString().strip()));
        }

        return new MatchResult(mappings, additionalSections);
    }

    private String resolveAdditionalType(String trimmedText) {
        String lower = trimmedText.toLowerCase(Locale.ROOT);
        if (lower.contains("protokol") || lower.contains("protocol")) return "protocol";
        if (lower.contains("addendum")) return "addendum";
        if (lower.contains("appendix") || lower.contains("ek")) return "appendix";
        return "additional";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ColumnBBox resolveActiveColumn(LanguageAnalysis languageAnalysis) {
        if (languageAnalysis == null || !languageAnalysis.multilingual()) {
            return null;
        }
        for (ColumnInfo col : languageAnalysis.columns()) {
            if (col.language().equals(languageAnalysis.activeLanguage())) {
                return col.bbox();
            }
        }
        return null;
    }

    private boolean blockInColumn(OcrBlock block, ColumnBBox column) {
        int centerX = block.bbox().x() + block.bbox().width() / 2;
        return centerX >= column.xStart() && centerX < column.xEnd();
    }

    private void expandBbox(Map<Integer, int[]> perPage, int pageNumber, BoundingBox bbox) {
        int x1 = bbox.x();
        int y1 = bbox.y();
        int x2 = bbox.x() + bbox.width();
        int y2 = bbox.y() + bbox.height();
        int[] existing = perPage.get(pageNumber);
        if (existing == null) {
            perPage.put(pageNumber, new int[]{x1, y1, x2, y2});
        } else {
            existing[0] = Math.min(existing[0], x1);
            existing[1] = Math.min(existing[1], y1);
            existing[2] = Math.max(existing[2], x2);
            existing[3] = Math.max(existing[3], y2);
        }
    }

    private boolean isAdditionalSectionStart(String trimmedText) {
        return ADDITIONAL_PATTERN.matcher(trimmedText).find();
    }

    private String extractClauseNumber(String trimmed) {
        if (trimmed.isEmpty()) return null;
        Matcher m = CLAUSE_NUMBER_PATTERN.matcher(trimmed);
        if (m.find()) {
            String raw = m.group(1);
            return raw.endsWith(".") ? raw.substring(0, raw.length() - 1) : raw;
        }
        Matcher madde = MADDE_PATTERN.matcher(trimmed);
        if (madde.find()) {
            return madde.group(2);
        }
        return null;
    }

    private String resolveDuplicateKey(String clauseNumber, Map<String, Integer> duplicateCount) {
        int count = duplicateCount.getOrDefault(clauseNumber, 0);
        duplicateCount.put(clauseNumber, count + 1);
        if (count == 0) {
            return clauseNumber;
        }
        return clauseNumber + "-duplicate-" + count;
    }
}
