package com.signedcontract.ocr;

import com.signedcontract.client.OcrClient;
import com.signedcontract.model.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Runs OCR on the first page and analyzes the x-coordinate distribution of
 * the resulting blocks to detect whether the document uses a single-column
 * (monolingual) or multi-column (multilingual, side-by-side translation)
 * layout.
 *
 * <p>Validates: Requirements 20.1–20.11</p>
 */
public class LanguageDetector {

    /** Minimum horizontal gap (as a fraction of page width) that separates two columns. */
    private static final double MIN_COLUMN_GAP_FRACTION = 0.04;

    private final OcrClient ocrClient;

    public LanguageDetector(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    /**
     * Detects the language / column structure of the document from its first page.
     *
     * @param pageImages    all page images (only the first is used for detection)
     * @param primaryLanguage optional config override (Req 20.5, 20.10); may be null
     * @param warnings      collector for non-critical WARN entries (Req 20.9)
     */
    public LanguageAnalysis detect(List<PageImage> pageImages, String primaryLanguage,
                                    com.signedcontract.warning.WarningCollector warnings)
            throws ContractAnalysisException {
        if (pageImages == null || pageImages.isEmpty()) {
            throw new IllegalArgumentException("pageImages must not be empty");
        }

        PageImage first = pageImages.get(0);
        PageOcrResult ocr = ocrClient.performOcr(first, primaryLanguage != null ? primaryLanguage : "tr");

        List<int[]> xRanges = new ArrayList<>(); // [xStart, xEnd] per block
        for (OcrBlock block : ocr.blocks()) {
            xRanges.add(new int[]{block.bbox().x(), block.bbox().x() + block.bbox().width()});
        }

        if (xRanges.isEmpty()) {
            if (warnings != null) {
                warnings.addWarning("LanguageDetector", Severity.WARN,
                        "İlk sayfada OCR bloğu bulunamadı; tek dilli/tek sütunlu düzen varsayıldı.");
            }
            String lang = primaryLanguage != null ? primaryLanguage : "tr";
            return new LanguageAnalysis(false, lang, List.of());
        }

        List<int[]> columns = clusterIntoColumns(xRanges, ocr.pageWidthPx());

        if (columns.size() <= 1) {
            String lang = primaryLanguage != null ? primaryLanguage : "tr";
            ColumnInfo single = new ColumnInfo(0, lang,
                    new ColumnBBox(0, ocr.pageWidthPx(), 0, ocr.pageHeightPx()));
            return new LanguageAnalysis(false, lang, List.of(single));
        }

        // Multilingual: build one ColumnInfo per detected column, assigning a
        // heuristically-detected language to each based on the text it contains.
        List<ColumnInfo> columnInfos = new ArrayList<>();
        int idx = 0;
        int bestArea = -1;
        int bestIdx = 0;
        for (int[] col : columns) {
            StringBuilder text = new StringBuilder();
            for (OcrBlock block : ocr.blocks()) {
                int center = block.bbox().x() + block.bbox().width() / 2;
                if (center >= col[0] && center < col[1]) {
                    text.append(block.content()).append(' ');
                }
            }
            String lang = detectLanguageHeuristic(text.toString());
            ColumnInfo info = new ColumnInfo(idx, lang,
                    new ColumnBBox(col[0], col[1], 0, ocr.pageHeightPx()));
            columnInfos.add(info);
            int area = (col[1] - col[0]) * ocr.pageHeightPx();
            if (area > bestArea) {
                bestArea = area;
                bestIdx = idx;
            }
            idx++;
        }

        String activeLanguage;
        if (primaryLanguage != null && !primaryLanguage.isBlank()) {
            activeLanguage = primaryLanguage;
        } else {
            activeLanguage = columnInfos.get(bestIdx).language();
        }

        return new LanguageAnalysis(true, activeLanguage, columnInfos);
    }

    /**
     * Groups 1-D horizontal ranges into columns by looking for gaps wider
     * than {@link #MIN_COLUMN_GAP_FRACTION} * pageWidth between the sorted
     * union of block extents.
     */
    private List<int[]> clusterIntoColumns(List<int[]> xRanges, int pageWidth) {
        List<int[]> sorted = new ArrayList<>(xRanges);
        sorted.sort(Comparator.comparingInt(r -> r[0]));

        int gapThreshold = (int) Math.round(pageWidth * MIN_COLUMN_GAP_FRACTION);
        List<int[]> columns = new ArrayList<>();
        int curStart = sorted.get(0)[0];
        int curEnd = sorted.get(0)[1];

        for (int i = 1; i < sorted.size(); i++) {
            int[] r = sorted.get(i);
            if (r[0] - curEnd > gapThreshold) {
                columns.add(new int[]{curStart, curEnd});
                curStart = r[0];
                curEnd = r[1];
            } else {
                curEnd = Math.max(curEnd, r[1]);
            }
        }
        columns.add(new int[]{curStart, curEnd});
        return columns;
    }

    /**
     * Very small heuristic language detector: Turkish-specific characters
     * strongly indicate "tr"; otherwise falls back to "en". This is
     * intentionally lightweight — a production system would call a proper
     * language-identification model/service.
     */
    private String detectLanguageHeuristic(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        long trChars = lower.chars()
                .filter(c -> "ğüşıöç".indexOf(c) >= 0)
                .count();
        if (trChars > 0) {
            return "tr";
        }
        return "en";
    }
}
