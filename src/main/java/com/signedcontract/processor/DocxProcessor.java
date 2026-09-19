package com.signedcontract.processor;

import com.signedcontract.model.AdditionalSection;
import com.signedcontract.model.ContractAnalysisException;
import com.signedcontract.model.OriginalClauseContent;
import com.signedcontract.model.OriginalContract;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Word (.docx) document into an {@link OriginalContract} with clause-level
 * Markdown content.
 *
 * <h3>Clause boundary detection</h3>
 * A new clause starts when a paragraph matches either:
 * <ul>
 *   <li>A heading-level style (Heading 1 – Heading 9), <em>or</em></li>
 *   <li>The clause-number regex {@code ^\d+(\.\d+)*\.?\s} (e.g. "1.", "1.1", "14.3.2 "), <em>or</em></li>
 *   <li>The "MADDE/Madde" prefix pattern {@code ^(MADDE|Madde)\s+\d+}</li>
 * </ul>
 *
 * <h3>Additional section detection</h3>
 * A paragraph is treated as the start of an {@code additional} block when it matches
 * one of the following patterns (case-insensitive): Ek Protokol, Ek-&lt;N&gt;, EK,
 * Addendum, Appendix.
 *
 * <h3>Duplicate clause numbers</h3>
 * If the same clause key appears more than once, subsequent occurrences receive a
 * {@code -duplicate-N} suffix (N starting at 1).
 */
public class DocxProcessor {

    // ── Configuration ────────────────────────────────────────────────────────

    private static final long DEFAULT_MAX_FILE_SIZE_MB = 100;

    /** Matches: "1.", "1.1", "1.1.1", "14.3.2 " etc. */
    private static final Pattern CLAUSE_NUMBER_PATTERN =
            Pattern.compile("^(\\d+(\\.\\d+)*\\.?)\\s");

    /** Matches: "MADDE 1", "Madde 1.2" etc. */
    private static final Pattern MADDE_PATTERN =
            Pattern.compile("^(MADDE|Madde)\\s+(\\d+(\\.\\d+)*)");

    /** Matches additional / appendix section headings. */
    private static final Pattern ADDITIONAL_PATTERN =
            Pattern.compile("^(EK\\s*PROTOKOL|EK\\s*-?\\s*\\d+|EK\\b|ADDENDUM|APPENDIX|" +
                            "Ek\\s*Protokol|Ek\\s*-?\\s*\\d+|Ek\\b|Addendum|Appendix)",
                    Pattern.CASE_INSENSITIVE);

    private final long maxFileSizeMb;

    // ── Constructors ─────────────────────────────────────────────────────────

    public DocxProcessor() {
        this.maxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB;
    }

    public DocxProcessor(long maxFileSizeMb) {
        this.maxFileSizeMb = maxFileSizeMb;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Validates and parses the given {@code .docx} file into an {@link OriginalContract}.
     *
     * @param docxPath absolute or relative path to the .docx file
     * @return structured contract with title, clauses (Markdown), and additional sections
     * @throws ContractAnalysisException if the file is invalid, corrupted, or too large
     * @throws IllegalArgumentException  if {@code docxPath} is null or the file does not exist
     */
    public OriginalContract parse(String docxPath) throws ContractAnalysisException {
        // ── Validate ─────────────────────────────────────────────────────────
        validateFile(docxPath);

        // ── Open with Apache POI ─────────────────────────────────────────────
        // Apache POI uses a streaming OOXML approach internally (SAX-backed) that
        // avoids XXE vulnerabilities because it does not invoke an XML parser that
        // resolves external entities on user-supplied content.
        try (InputStream in = new FileInputStream(docxPath);
             XWPFDocument doc = new XWPFDocument(in)) {

            return buildContract(doc);

        } catch (org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            throw new ContractAnalysisException(
                    "Dosya geçerli bir Word belgesi değil: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ContractAnalysisException(
                    "DOCX dosyası bozuk veya okunamıyor: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ContractAnalysisException(
                    "DOCX işlenirken beklenmeyen hata: " + e.getMessage(), e);
        }
    }

    // ── File Validation ──────────────────────────────────────────────────────

    private void validateFile(String docxPath) throws ContractAnalysisException {
        if (docxPath == null) {
            throw new IllegalArgumentException("DOCX path must not be null");
        }
        File file = new File(docxPath);
        if (!file.exists() || file.isDirectory()) {
            throw new IllegalArgumentException("File does not exist: " + docxPath);
        }

        // Extension check (Requirement 8.1)
        if (!docxPath.toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw new ContractAnalysisException(
                    "Geçersiz dosya formatı: yalnızca .docx kabul edilmektedir");
        }

        // File size check (Requirement 22.3)
        if (file.length() > maxFileSizeMb * 1024L * 1024L) {
            throw new ContractAnalysisException(
                    "Dosya boyutu izin verilen maksimum boyutu aşıyor: " + maxFileSizeMb + " MB");
        }

        // Magic bytes: ZIP (PK) — all OOXML files start with 0x50 0x4B (Requirement 8.2)
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            int read = fis.read(magic);
            if (read < 2 || magic[0] != 0x50 || magic[1] != 0x4B) {
                throw new ContractAnalysisException(
                        "Geçersiz dosya formatı: yalnızca .docx kabul edilmektedir");
            }
        } catch (ContractAnalysisException e) {
            throw e;
        } catch (IOException e) {
            throw new ContractAnalysisException(
                    "DOCX dosyası okunamıyor: " + e.getMessage(), e);
        }
    }

    // ── Document Parsing ─────────────────────────────────────────────────────

    /**
     * Traverses the document body elements (paragraphs and tables) and groups
     * them into clauses and additional sections.
     */
    private OriginalContract buildContract(XWPFDocument doc) {
        String title = extractTitle(doc);

        // Accumulated state
        LinkedHashMap<String, List<String>> clauseLines = new LinkedHashMap<>();
        List<AdditionalSection> additionalSections = new ArrayList<>();

        // Tracking
        String currentClauseKey = null;       // null → preamble (before first clause)
        boolean inAdditional = false;
        String additionalType = null;
        List<String> additionalLines = null;

        // Duplicate tracking: base key → count of occurrences
        Map<String, Integer> duplicateCount = new HashMap<>();

        for (IBodyElement element : doc.getBodyElements()) {
            if (element instanceof XWPFParagraph para) {
                String text = para.getText();
                if (text == null) text = "";
                String trimmed = text.trim();

                // ── Check for additional section start ───────────────────────
                if (isAdditionalSectionStart(trimmed)) {
                    // Finalize previous additional block if open
                    if (inAdditional && additionalLines != null) {
                        additionalSections.add(new AdditionalSection(
                                additionalType, String.join("\n", additionalLines).trim()));
                    }
                    inAdditional = true;
                    additionalType = resolveAdditionalType(trimmed);
                    additionalLines = new ArrayList<>();
                    additionalLines.add(paragraphToMarkdown(para));
                    continue;
                }

                if (inAdditional) {
                    additionalLines.add(paragraphToMarkdown(para));
                    continue;
                }

                // ── Check for clause boundary ─────────────────────────────────
                String clauseNum = extractClauseNumber(para);
                if (clauseNum != null) {
                    // Resolve duplicate suffix
                    String finalKey = resolveDuplicateKey(clauseNum, duplicateCount);

                    currentClauseKey = finalKey;
                    clauseLines.put(currentClauseKey, new ArrayList<>());
                    clauseLines.get(currentClauseKey).add(paragraphToMarkdown(para));
                } else {
                    // Append to current clause or preamble
                    if (currentClauseKey != null) {
                        clauseLines.get(currentClauseKey).add(paragraphToMarkdown(para));
                    }
                    // Preamble paragraphs before first clause are silently ignored
                    // (title is already extracted separately)
                }

            } else if (element instanceof XWPFTable table) {
                String tableMarkdown = tableToMarkdown(table);

                if (inAdditional) {
                    if (additionalLines != null) additionalLines.add(tableMarkdown);
                    continue;
                }

                if (currentClauseKey != null) {
                    clauseLines.get(currentClauseKey).add(tableMarkdown);
                }
            }
        }

        // Finalize last additional block
        if (inAdditional && additionalLines != null) {
            additionalSections.add(new AdditionalSection(
                    additionalType, String.join("\n", additionalLines).trim()));
        }

        // Build clauses map
        LinkedHashMap<String, OriginalClauseContent> clauses = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : clauseLines.entrySet()) {
            String content = buildClauseContent(entry.getValue());
            clauses.put(entry.getKey(), new OriginalClauseContent(content));
        }

        return new OriginalContract(title, clauses, additionalSections);
    }

    // ── Title Extraction ─────────────────────────────────────────────────────

    /**
     * Extracts the contract title from the first "Title" or "Heading 1" styled
     * paragraph (or the document core properties), falling back to the first
     * non-empty paragraph text.
     */
    private String extractTitle(XWPFDocument doc) {
        // Prefer core-properties title
        if (doc.getProperties() != null
                && doc.getProperties().getCoreProperties() != null) {
            String coreTitle = doc.getProperties().getCoreProperties().getTitle();
            if (coreTitle != null && !coreTitle.isBlank()) {
                return coreTitle.trim();
            }
        }

        // Find first "Title" or "Heading 1" paragraph
        for (XWPFParagraph para : doc.getParagraphs()) {
            String style = para.getStyle();
            String text = para.getText();
            if (text == null || text.isBlank()) continue;

            if (style != null) {
                String lowerStyle = style.toLowerCase(Locale.ROOT);
                if (lowerStyle.contains("title") || lowerStyle.equals("heading1")
                        || lowerStyle.equals("başlık1") || lowerStyle.startsWith("heading 1")) {
                    return text.trim();
                }
            }
        }

        // Fall back to first non-empty paragraph that is not a clause
        for (XWPFParagraph para : doc.getParagraphs()) {
            String text = para.getText();
            if (text != null && !text.isBlank()) {
                String trimmed = text.trim();
                if (extractClauseNumber(para) == null && !isAdditionalSectionStart(trimmed)) {
                    return trimmed;
                }
            }
        }

        return "";
    }

    // ── Clause Number Extraction ─────────────────────────────────────────────

    /**
     * Returns the normalised clause number if the paragraph starts a new clause,
     * or {@code null} if it does not.
     *
     * <p>A clause boundary is detected when:</p>
     * <ol>
     *   <li>The paragraph style is a heading (Heading 1–9), <em>and</em>
     *       the text starts with {@code CLAUSE_NUMBER_PATTERN} or {@code MADDE_PATTERN}.</li>
     *   <li>The paragraph text (regardless of style) matches
     *       {@code CLAUSE_NUMBER_PATTERN} or {@code MADDE_PATTERN}.</li>
     * </ol>
     */
    String extractClauseNumber(XWPFParagraph para) {
        String text = para.getText();
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();

        // Numeric clause number: "1.", "1.1", "1.1.1 " etc.
        Matcher m = CLAUSE_NUMBER_PATTERN.matcher(trimmed);
        if (m.find()) {
            return normalizeClauseNumber(m.group(1));
        }

        // MADDE/Madde prefix: "MADDE 1", "Madde 1.2"
        Matcher madde = MADDE_PATTERN.matcher(trimmed);
        if (madde.find()) {
            return normalizeClauseNumber(madde.group(2));
        }

        // Heading styles with clause number inside
        String style = para.getStyle();
        if (style != null && isHeadingStyle(style)) {
            // Check if heading text contains a clause number anywhere
            Matcher hm = CLAUSE_NUMBER_PATTERN.matcher(trimmed);
            if (hm.find()) {
                return normalizeClauseNumber(hm.group(1));
            }
            Matcher hmm = MADDE_PATTERN.matcher(trimmed);
            if (hmm.find()) {
                return normalizeClauseNumber(hmm.group(2));
            }
        }

        return null;
    }

    /** Removes trailing dot from a clause number string (e.g. "1." → "1"). */
    private String normalizeClauseNumber(String raw) {
        return raw.endsWith(".") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private boolean isHeadingStyle(String style) {
        if (style == null) return false;
        String lower = style.toLowerCase(Locale.ROOT);
        // Matches "heading1"…"heading9", "heading 1"…"heading 9"
        return lower.matches("heading\\s*[1-9]")
                || lower.matches("başlık\\s*[1-9]")
                || lower.matches("overschrift\\s*[1-9]");
    }

    // ── Duplicate Key Resolution ─────────────────────────────────────────────

    /**
     * Returns the final unique key for a clause number, appending {@code -duplicate-N}
     * if the same base key has been seen before (Requirement 29.1, 29.2).
     */
    String resolveDuplicateKey(String clauseNumber, Map<String, Integer> duplicateCount) {
        int count = duplicateCount.getOrDefault(clauseNumber, 0);
        duplicateCount.put(clauseNumber, count + 1);
        if (count == 0) {
            return clauseNumber;
        }
        return clauseNumber + "-duplicate-" + count;
    }

    // ── Additional Section Detection ─────────────────────────────────────────

    private boolean isAdditionalSectionStart(String trimmedText) {
        return ADDITIONAL_PATTERN.matcher(trimmedText).find();
    }

    private String resolveAdditionalType(String trimmedText) {
        String lower = trimmedText.toLowerCase(Locale.ROOT);
        if (lower.contains("protokol") || lower.contains("protocol")) return "protocol";
        if (lower.contains("addendum")) return "addendum";
        if (lower.contains("appendix") || lower.contains("ek")) return "appendix";
        return "additional";
    }

    // ── Paragraph to Markdown ─────────────────────────────────────────────────

    /**
     * Converts a single {@link XWPFParagraph} to its Markdown representation.
     *
     * <ul>
     *   <li>Heading styles → ATX heading ({@code #} … {@code #######})</li>
     *   <li>List paragraph → {@code - } bullet or numbered list item</li>
     *   <li>Bold runs → {@code **bold**}</li>
     *   <li>Italic runs → {@code *italic*}</li>
     * </ul>
     */
    String paragraphToMarkdown(XWPFParagraph para) {
        String style = para.getStyle();
        String rawText = buildRunsMarkdown(para);

        if (rawText.isBlank()) return "";

        // Heading level
        if (style != null) {
            int level = headingLevel(style);
            if (level > 0) {
                return "#".repeat(level) + " " + rawText.trim();
            }
        }

        // List items
        if (para.getNumID() != null) {
            // Determine if ordered or unordered — default to unordered
            return "- " + rawText.trim();
        }

        return rawText.trim();
    }

    /** Returns 1–6 for heading styles, 0 for non-headings. */
    private int headingLevel(String style) {
        if (style == null) return 0;
        String lower = style.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
        for (int i = 1; i <= 6; i++) {
            if (lower.equals("heading" + i) || lower.equals("başlık" + i)) return i;
        }
        // "Title" style maps to H1
        if (lower.equals("title") || lower.equals("başlık")) return 1;
        return 0;
    }

    /**
     * Iterates over runs and applies inline Markdown formatting (bold, italic).
     */
    private String buildRunsMarkdown(XWPFParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            String text = run.getText(0);
            if (text == null || text.isEmpty()) continue;

            boolean bold   = run.isBold();
            boolean italic = run.isItalic();

            if (bold && italic) {
                sb.append("***").append(text).append("***");
            } else if (bold) {
                sb.append("**").append(text).append("**");
            } else if (italic) {
                sb.append("*").append(text).append("*");
            } else {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    // ── Table to Markdown ─────────────────────────────────────────────────────

    /**
     * Converts an {@link XWPFTable} to a GitHub-Flavored Markdown table.
     *
     * <p>If the table has at least one row, the first row is treated as the header.
     * The separator row (e.g. {@code | --- | --- |}) is always emitted.</p>
     */
    String tableToMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        // Header row
        XWPFTableRow headerRow = rows.get(0);
        List<String> headerCells = rowToCells(headerRow);
        sb.append("| ").append(String.join(" | ", headerCells)).append(" |");
        sb.append("\n");

        // Separator row
        sb.append("| ");
        for (int i = 0; i < headerCells.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append("---");
        }
        sb.append(" |");
        sb.append("\n");

        // Data rows
        for (int r = 1; r < rows.size(); r++) {
            List<String> cells = rowToCells(rows.get(r));
            // Pad cells to header width
            while (cells.size() < headerCells.size()) cells.add("");
            sb.append("| ").append(String.join(" | ", cells)).append(" |");
            sb.append("\n");
        }

        return sb.toString().stripTrailing();
    }

    private List<String> rowToCells(XWPFTableRow row) {
        List<String> cells = new ArrayList<>();
        for (XWPFTableCell cell : row.getTableCells()) {
            // Collect all paragraphs in cell
            StringBuilder cellText = new StringBuilder();
            for (XWPFParagraph p : cell.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    if (cellText.length() > 0) cellText.append(" ");
                    cellText.append(t.trim());
                }
            }
            // Escape pipe characters inside cells
            cells.add(cellText.toString().replace("|", "\\|"));
        }
        return cells;
    }

    // ── Clause Content Assembly ───────────────────────────────────────────────

    /**
     * Joins Markdown lines for a clause, removing blank-line clusters and
     * ensuring proper paragraph separation.
     */
    private String buildClauseContent(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        boolean lastWasBlank = false;
        for (String line : lines) {
            if (line.isBlank()) {
                if (!lastWasBlank && sb.length() > 0) {
                    sb.append("\n\n");
                    lastWasBlank = true;
                }
            } else {
                sb.append(line).append("\n");
                lastWasBlank = false;
            }
        }
        return sb.toString().strip();
    }
}
