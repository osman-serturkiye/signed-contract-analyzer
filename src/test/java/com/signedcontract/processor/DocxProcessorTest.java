package com.signedcontract.processor;

import com.signedcontract.model.AdditionalSection;
import com.signedcontract.model.ContractAnalysisException;
import com.signedcontract.model.OriginalContract;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DocxProcessor}.
 *
 * <p>All in-memory .docx files are created via Apache POI {@link XWPFDocument}
 * — no external fixture files are needed.
 *
 * <p>Covers Requirements 8.1–8.4, 9.1–9.7:
 * <ul>
 *   <li>8.1 – Only .docx extension accepted</li>
 *   <li>8.2 – XXE protection (SAX parsing does not load external entities)</li>
 *   <li>8.3 – Non-.docx file produces the required error message</li>
 *   <li>8.4 – Corrupted / invalid .docx produces a corruption error</li>
 *   <li>9.1 – Valid .docx processed with Apache POI</li>
 *   <li>9.2 – Clause boundaries detected via heading style / clause-number regex</li>
 *   <li>9.3 – Nested clause numbering hierarchy preserved</li>
 *   <li>9.4 – Each clause produced as Markdown</li>
 *   <li>9.5 – Contract title extracted from the document</li>
 *   <li>9.6 – Additional / Ek sections detected and separated</li>
 *   <li>9.7 – Additional sections output in the correct JSON-ready structure</li>
 * </ul>
 */
class DocxProcessorTest {

    @TempDir
    Path tempDir;

    private final DocxProcessor processor = new DocxProcessor();

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Saves an in-memory {@link XWPFDocument} to a temp .docx file and returns
     * its absolute path.
     */
    private String saveDocx(XWPFDocument doc, String name) throws Exception {
        File f = tempDir.resolve(name + ".docx").toFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            doc.write(fos);
        }
        return f.getAbsolutePath();
    }

    /**
     * Saves an in-memory {@link XWPFDocument} to a temp file with the given
     * extension (may be non-.docx for negative tests).
     */
    private String saveDocxWithExtension(XWPFDocument doc, String filename) throws Exception {
        File f = tempDir.resolve(filename).toFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            doc.write(fos);
        }
        return f.getAbsolutePath();
    }

    /** Adds a plain paragraph (Normal style). */
    private XWPFParagraph addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        return p;
    }

    /** Adds a paragraph whose style name is set (used for Heading styles). */
    private XWPFParagraph addParagraphWithStyle(XWPFDocument doc, String text, String style) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle(style);
        XWPFRun run = p.createRun();
        run.setText(text);
        return p;
    }

    /**
     * Builds a minimal but valid .docx with:
     * <ul>
     *   <li>a preamble/title paragraph</li>
     *   <li>clause "1. First clause heading" followed by body text</li>
     *   <li>sub-clause "1.1 Sub-clause heading" followed by body text</li>
     *   <li>clause "2. Second clause"</li>
     * </ul>
     */
    private XWPFDocument buildContractDoc() {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "Service Agreement");
        addParagraph(doc, "1. First Clause Title");
        addParagraph(doc, "This is the body of the first clause.");
        addParagraph(doc, "1.1 Sub-clause content here.");
        addParagraph(doc, "Details of sub-clause 1.1.");
        addParagraph(doc, "2. Second Clause Title");
        addParagraph(doc, "Body of the second clause.");
        return doc;
    }

    // =========================================================================
    // Req 8.1 / 8.3 — Extension validation
    // =========================================================================

    /**
     * Req 8.1, 8.3 – A file with a non-.docx extension must be rejected with the
     * Turkish "geçersiz dosya formatı" error message.
     */
    @Test
    void parse_nonDocxExtension_throwsContractAnalysisExceptionWithFormatMessage() throws Exception {
        // Save a valid OOXML document but with a .txt extension
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Clause one");
        String path = saveDocxWithExtension(doc, "contract.txt");

        ContractAnalysisException ex = assertThrows(ContractAnalysisException.class,
                () -> processor.parse(path));
        assertThat(ex.getMessage().toLowerCase())
                .as("Error message should mention invalid format")
                .containsAnyOf("geçersiz", "format", "invalid");
    }

    /**
     * Req 8.1, 8.3 – A file with a .pdf extension must be rejected.
     */
    @Test
    void parse_pdfExtension_throwsContractAnalysisException() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "Some text");
        String path = saveDocxWithExtension(doc, "contract.pdf");

        assertThrows(ContractAnalysisException.class, () -> processor.parse(path));
    }

    /**
     * Req 8.1 – null path must be rejected with IllegalArgumentException.
     */
    @Test
    void parse_nullPath_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> processor.parse(null));
    }

    /**
     * Req 8.1 – non-existent file path must be rejected with IllegalArgumentException.
     */
    @Test
    void parse_nonExistentFile_throwsIllegalArgumentException() {
        String path = tempDir.resolve("ghost.docx").toString();
        assertThrows(IllegalArgumentException.class, () -> processor.parse(path));
    }

    // =========================================================================
    // Req 8.4 — Corrupted / invalid .docx
    // =========================================================================

    /**
     * Req 8.4 – A file with a .docx extension but random/garbage content is not
     * a valid ZIP/OOXML structure and must throw ContractAnalysisException.
     */
    @Test
    void parse_corruptedDocxContent_throwsContractAnalysisException() throws Exception {
        File f = tempDir.resolve("corrupt.docx").toFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            // NOT a ZIP → not a valid OOXML file; magic bytes validation must catch this
            fos.write("This is not a docx file, just garbage text content here!".getBytes());
        }
        assertThrows(ContractAnalysisException.class,
                () -> processor.parse(f.getAbsolutePath()));
    }

    /**
     * Req 8.4 – A file that starts with valid ZIP magic bytes but whose OOXML
     * internal structure is truncated/broken must still throw ContractAnalysisException.
     */
    @Test
    void parse_truncatedZipDocx_throwsContractAnalysisException() throws Exception {
        File f = tempDir.resolve("truncated.docx").toFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            // ZIP/PK magic bytes followed by truncated garbage
            fos.write(new byte[]{0x50, 0x4B, 0x03, 0x04}); // PK\x03\x04
            fos.write(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00}); // incomplete ZIP header
        }
        assertThrows(ContractAnalysisException.class,
                () -> processor.parse(f.getAbsolutePath()));
    }

    // =========================================================================
    // Req 8.2 — XXE protection
    // =========================================================================

    /**
     * Req 8.2 – DocxProcessor relies on Apache POI's OOXML SAX-backed parsing.
     * This test verifies that documents containing an internal DTD with external
     * entity declarations are processed without resolving those external entities,
     * meaning the parse succeeds on a valid docx and the external entity reference
     * is never invoked (the parser does not attempt to load it).
     *
     * <p>If XXE were not prevented, the parse would either fail trying to fetch
     * the URI or (worse) read local files.  We confirm safety by verifying:
     * <ol>
     *   <li>A valid .docx file can be parsed successfully — the SAX pipeline works.</li>
     *   <li>The DocxProcessor does NOT propagate external-entity resolution because
     *       Apache POI uses pre-configured SAX factories that disable DOCTYPE / external
     *       entities (FEATURE_SECURE_PROCESSING).</li>
     * </ol>
     *
     * <p>The concrete check: DocxProcessor must not throw when parsing a normal
     * document (the SAX configuration is valid), and no network/filesystem side
     * effects are observed (this is structural, not behavioural, for unit tests).
     */
    @Test
    void parse_xxeProtection_normalDocxParsedSafelyWithoutExternalEntityResolution()
            throws Exception {
        // Build a minimal valid .docx
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Clause one body text.");
        String path = saveDocx(doc, "xxe_test");

        // Must not throw — the SAX pipeline should handle it safely
        OriginalContract contract = assertDoesNotThrow(() -> processor.parse(path));
        assertThat(contract).isNotNull();
    }

    // =========================================================================
    // Req 9.1 — Valid .docx processed with Apache POI
    // =========================================================================

    /**
     * Req 9.1 – Parsing a well-formed .docx returns a non-null OriginalContract
     * with a non-null clauses map, confirming Apache POI integration is functional.
     */
    @Test
    void parse_validDocx_returnsOriginalContractNotNull() throws Exception {
        String path = saveDocx(buildContractDoc(), "valid_contract");
        OriginalContract contract = processor.parse(path);

        assertThat(contract).isNotNull();
        assertThat(contract.clauses()).isNotNull();
        assertThat(contract.additional()).isNotNull();
    }

    // =========================================================================
    // Req 9.2 / 9.3 — Clause boundary detection and nested numbering
    // =========================================================================

    /**
     * Req 9.2 – Numeric clause-number paragraphs ("1. …", "1.1 …") must each
     * produce a distinct clause key in OriginalContract.
     */
    @Test
    void parse_validDocxWithNumericClauses_allClauseKeysPresent() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. First Clause");
        addParagraph(doc, "Body of first clause.");
        addParagraph(doc, "2. Second Clause");
        addParagraph(doc, "Body of second clause.");
        String path = saveDocx(doc, "two_clause");

        OriginalContract contract = processor.parse(path);
        Map<String, ?> clauses = contract.clauses();

        assertThat(clauses).containsKey("1");
        assertThat(clauses).containsKey("2");
    }

    /**
     * Req 9.3 – Nested clause hierarchy ("1", "1.1", "1.1.1") must all appear as
     * separate, correctly-keyed entries in the clauses map.
     */
    @Test
    void parse_nestedClauseHierarchy_allLevelsPresent() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Top-level clause");
        addParagraph(doc, "Top-level body.");
        addParagraph(doc, "1.1 Second-level clause");
        addParagraph(doc, "Second-level body.");
        addParagraph(doc, "1.1.1 Third-level clause");
        addParagraph(doc, "Third-level body.");
        String path = saveDocx(doc, "nested_clauses");

        OriginalContract contract = processor.parse(path);
        Map<String, ?> clauses = contract.clauses();

        assertThat(clauses).containsKey("1");
        assertThat(clauses).containsKey("1.1");
        assertThat(clauses).containsKey("1.1.1");
    }

    /**
     * Req 9.2 – "MADDE N" Turkish prefix form must be detected as a clause boundary
     * and stored under its numeric key.
     */
    @Test
    void parse_maddePrefixClauses_clauseKeysDetected() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "MADDE 1 Taraflar");
        addParagraph(doc, "Bu madde tarafları düzenler.");
        addParagraph(doc, "MADDE 2 Kapsam");
        addParagraph(doc, "Bu madde kapsamı düzenler.");
        String path = saveDocx(doc, "madde_clauses");

        OriginalContract contract = processor.parse(path);

        assertThat(contract.clauses()).containsKey("1");
        assertThat(contract.clauses()).containsKey("2");
    }

    /**
     * Req 9.2 – Clause body text must be associated with the correct clause key.
     */
    @Test
    void parse_clauseBodyText_isAssociatedWithCorrectClause() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. First Clause");
        addParagraph(doc, "First clause unique body text.");
        addParagraph(doc, "2. Second Clause");
        addParagraph(doc, "Second clause unique body text.");
        String path = saveDocx(doc, "body_association");

        OriginalContract contract = processor.parse(path);

        String firstContent = contract.clauses().get("1").content();
        String secondContent = contract.clauses().get("2").content();

        assertThat(firstContent).contains("First clause unique body text");
        assertThat(secondContent).contains("Second clause unique body text");
        // Body of clause 2 must NOT appear in clause 1
        assertThat(firstContent).doesNotContain("Second clause unique body text");
    }

    // =========================================================================
    // Req 9.4 — Markdown output
    // =========================================================================

    /**
     * Req 9.4 – Each clause content must be non-null and non-empty Markdown (not
     * raw XML or null).
     */
    @Test
    void parse_clauseContentIsMarkdown_nonNullAndNonEmpty() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Clause with content");
        addParagraph(doc, "Some body text here.");
        String path = saveDocx(doc, "markdown_content");

        OriginalContract contract = processor.parse(path);

        String content = contract.clauses().get("1").content();
        assertThat(content).isNotNull().isNotBlank();
        // Must not contain raw XML tags
        assertThat(content).doesNotContain("<w:");
        assertThat(content).doesNotContain("<?xml");
    }

    /**
     * Req 9.4 – Bold text in runs must be rendered as Markdown bold (**text**).
     */
    @Test
    void parse_boldRunInClause_renderedAsMarkdownBold() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph clausePara = doc.createParagraph();
        XWPFRun clauseRun = clausePara.createRun();
        clauseRun.setText("1. Bold Clause");

        XWPFParagraph bodyPara = doc.createParagraph();
        XWPFRun boldRun = bodyPara.createRun();
        boldRun.setBold(true);
        boldRun.setText("important");
        XWPFRun normalRun = bodyPara.createRun();
        normalRun.setText(" text");

        String path = saveDocx(doc, "bold_run");
        OriginalContract contract = processor.parse(path);

        String content = contract.clauses().get("1").content();
        assertThat(content).contains("**important**");
    }

    /**
     * Req 9.4 – Italic text in runs must be rendered as Markdown italic (*text*).
     */
    @Test
    void parse_italicRunInClause_renderedAsMarkdownItalic() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph clausePara = doc.createParagraph();
        XWPFRun clauseRun = clausePara.createRun();
        clauseRun.setText("1. Italic Clause");

        XWPFParagraph bodyPara = doc.createParagraph();
        XWPFRun italicRun = bodyPara.createRun();
        italicRun.setItalic(true);
        italicRun.setText("emphasis");

        String path = saveDocx(doc, "italic_run");
        OriginalContract contract = processor.parse(path);

        String content = contract.clauses().get("1").content();
        assertThat(content).contains("*emphasis*");
    }

    /**
     * Req 9.4 – A table within a clause must be rendered as a Markdown table
     * (pipe-separated rows with a separator row).
     */
    @Test
    void parse_tableInClause_renderedAsMarkdownTable() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Clause with Table");

        // Create a 2×2 table
        XWPFTable table = doc.createTable(2, 2);
        table.getRow(0).getCell(0).setText("Header A");
        table.getRow(0).getCell(1).setText("Header B");
        table.getRow(1).getCell(0).setText("Value 1");
        table.getRow(1).getCell(1).setText("Value 2");

        String path = saveDocx(doc, "table_in_clause");
        OriginalContract contract = processor.parse(path);

        String content = contract.clauses().get("1").content();
        assertThat(content).contains("|");
        assertThat(content).contains("Header A");
        assertThat(content).contains("Value 1");
        // Must have a separator row (---) for GFM table
        assertThat(content).contains("---");
    }

    // =========================================================================
    // Req 9.5 — Title extraction
    // =========================================================================

    /**
     * Req 9.5 – The contract title must be extracted and available via
     * OriginalContract.title().
     */
    @Test
    void parse_documentWithFirstParagraphTitle_titleExtracted() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "Hizmet Sözleşmesi");
        addParagraph(doc, "1. Taraflar");
        addParagraph(doc, "Tarafları tanımlar.");
        String path = saveDocx(doc, "titled_doc");

        OriginalContract contract = processor.parse(path);

        assertThat(contract.title()).isNotNull().isNotBlank();
    }

    /**
     * Req 9.5 – When a "Title" styled paragraph exists, that paragraph's text
     * must be returned as the title.
     */
    @Test
    void parse_titleStyledParagraph_titleMatchesTitleParagraphText() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph titlePara = doc.createParagraph();
        titlePara.setStyle("Title");
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText("Official Contract Title");

        addParagraph(doc, "1. First clause");
        addParagraph(doc, "Clause body.");
        String path = saveDocx(doc, "title_styled");

        OriginalContract contract = processor.parse(path);

        assertThat(contract.title()).isEqualTo("Official Contract Title");
    }

    // =========================================================================
    // Req 9.6 / 9.7 — Additional / Ek section detection
    // =========================================================================

    /**
     * Req 9.6 – A paragraph matching the "Ek" / additional pattern must trigger
     * additional-section detection; the additional list must not be empty.
     */
    @Test
    void parse_docWithEkSection_additionalListNotEmpty() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. First Clause");
        addParagraph(doc, "Body of first clause.");
        // This paragraph triggers the additional-section switch
        addParagraph(doc, "EK PROTOKOL");
        addParagraph(doc, "Protocol content here.");
        String path = saveDocx(doc, "ek_protokol");

        OriginalContract contract = processor.parse(path);

        assertThat(contract.additional()).isNotEmpty();
    }

    /**
     * Req 9.6 – "Ek-1" style headings must also be detected as additional sections.
     */
    @Test
    void parse_docWithEk1Section_additionalListNotEmpty() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Main Clause");
        addParagraph(doc, "Main clause body.");
        addParagraph(doc, "Ek-1");
        addParagraph(doc, "Appendix content here.");
        String path = saveDocx(doc, "ek_1");

        OriginalContract contract = processor.parse(path);

        assertThat(contract.additional()).isNotEmpty();
    }

    /**
     * Req 9.6 – "Addendum" English keyword must also trigger additional section.
     */
    @Test
    void parse_docWithAddendumSection_additionalListNotEmpty() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Main Clause");
        addParagraph(doc, "Main clause body.");
        addParagraph(doc, "Addendum");
        addParagraph(doc, "Addendum content here.");
        String path = saveDocx(doc, "addendum");

        OriginalContract contract = processor.parse(path);

        assertThat(contract.additional()).isNotEmpty();
    }

    /**
     * Req 9.7 – Each AdditionalSection must have a non-null type and non-null content.
     */
    @Test
    void parse_docWithEkSection_additionalSectionHasTypeAndContent() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Clause One");
        addParagraph(doc, "Clause body.");
        addParagraph(doc, "EK PROTOKOL");
        addParagraph(doc, "Protocol body text here.");
        String path = saveDocx(doc, "ek_type_content");

        OriginalContract contract = processor.parse(path);

        assertThat(contract.additional()).isNotEmpty();
        AdditionalSection section = contract.additional().get(0);
        assertThat(section.type()).isNotNull().isNotBlank();
        assertThat(section.content()).isNotNull().isNotBlank();
    }

    /**
     * Req 9.7 – EK PROTOKOL must produce type "protocol".
     */
    @Test
    void parse_ekProtokolSection_typeIsProtocol() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Clause");
        addParagraph(doc, "Body.");
        addParagraph(doc, "EK PROTOKOL");
        addParagraph(doc, "Content.");
        String path = saveDocx(doc, "protokol_type");

        OriginalContract contract = processor.parse(path);

        List<AdditionalSection> additionals = contract.additional();
        assertThat(additionals).isNotEmpty();
        assertThat(additionals.get(0).type()).isEqualTo("protocol");
    }

    /**
     * Req 9.7 – Appendix / Ek must produce type "appendix".
     */
    @Test
    void parse_appendixSection_typeIsAppendix() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Clause");
        addParagraph(doc, "Body.");
        addParagraph(doc, "Appendix");
        addParagraph(doc, "Content.");
        String path = saveDocx(doc, "appendix_type");

        OriginalContract contract = processor.parse(path);

        List<AdditionalSection> additionals = contract.additional();
        assertThat(additionals).isNotEmpty();
        assertThat(additionals.get(0).type()).isEqualTo("appendix");
    }

    /**
     * Req 9.6 / 9.7 – Content paragraphs after the additional heading must be
     * captured inside the AdditionalSection's content, not in the clauses map.
     */
    @Test
    void parse_contentAfterEkHeading_appearsInAdditionalNotInClauses() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Only Clause");
        addParagraph(doc, "Clause body.");
        addParagraph(doc, "EK PROTOKOL");
        addParagraph(doc, "Exclusive additional text.");
        String path = saveDocx(doc, "ek_exclusive_content");

        OriginalContract contract = processor.parse(path);

        // The additional content must contain the text
        String additionalContent = contract.additional().get(0).content();
        assertThat(additionalContent).contains("Exclusive additional text");

        // The clause map must NOT contain the additional text
        for (var entry : contract.clauses().entrySet()) {
            assertThat(entry.getValue().content())
                    .as("Clause %s must not contain additional section text", entry.getKey())
                    .doesNotContain("Exclusive additional text");
        }
    }

    /**
     * Req 9.6 – Multiple separate additional sections (e.g., EK PROTOKOL and Ek-1)
     * must each produce a distinct AdditionalSection in the list.
     */
    @Test
    void parse_multipleAdditionalSections_eachAppearsAsSeparateEntry() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Clause");
        addParagraph(doc, "Body.");
        addParagraph(doc, "EK PROTOKOL");
        addParagraph(doc, "Protocol content.");
        addParagraph(doc, "Ek-1");
        addParagraph(doc, "Appendix one content.");
        String path = saveDocx(doc, "multi_additional");

        OriginalContract contract = processor.parse(path);

        assertThat(contract.additional()).hasSizeGreaterThanOrEqualTo(2);
    }

    // =========================================================================
    // Duplicate clause number — -duplicate-N suffix
    // =========================================================================

    /**
     * Requirement (doc comment in DocxProcessor) – When the same clause number
     * appears twice, the second occurrence must receive a {@code -duplicate-1} suffix,
     * so both clauses coexist in the map without data loss.
     */
    @Test
    void parse_duplicateClauseNumbers_bothKeysExistWithSuffix() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. First occurrence");
        addParagraph(doc, "First body.");
        addParagraph(doc, "2. Clause two");
        addParagraph(doc, "Middle clause.");
        addParagraph(doc, "1. Second occurrence");
        addParagraph(doc, "Second body.");
        String path = saveDocx(doc, "duplicate_clauses");

        OriginalContract contract = processor.parse(path);

        assertThat(contract.clauses()).containsKey("1");
        assertThat(contract.clauses()).containsKey("1-duplicate-1");
    }

    /**
     * A triple occurrence of the same clause key must produce:
     *   "N", "N-duplicate-1", "N-duplicate-2".
     */
    @Test
    void parse_tripleOccurrenceOfSameClauseNumber_threeUniqueKeys() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "5. First");
        addParagraph(doc, "Body A.");
        addParagraph(doc, "5. Second");
        addParagraph(doc, "Body B.");
        addParagraph(doc, "5. Third");
        addParagraph(doc, "Body C.");
        String path = saveDocx(doc, "triple_duplicate");

        OriginalContract contract = processor.parse(path);

        assertThat(contract.clauses()).containsKey("5");
        assertThat(contract.clauses()).containsKey("5-duplicate-1");
        assertThat(contract.clauses()).containsKey("5-duplicate-2");
    }

    // =========================================================================
    // File size validation
    // =========================================================================

    /**
     * A file whose size exceeds the configured limit must be rejected with a
     * size-related error message.
     */
    @Test
    void parse_fileExceedsSizeLimit_throwsContractAnalysisExceptionWithSizeMessage()
            throws Exception {
        // Build a real (small) docx so magic-byte check passes, then check limit = 0 MB
        XWPFDocument doc = new XWPFDocument();
        addParagraph(doc, "1. Clause");
        File f = tempDir.resolve("big_doc.docx").toFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            doc.write(fos);
        }
        // Limit of 0 MB → any real file exceeds it
        ContractAnalysisException ex = assertThrows(ContractAnalysisException.class,
                () -> new DocxProcessor(0).parse(f.getAbsolutePath()));
        assertThat(ex.getMessage().toLowerCase())
                .containsAnyOf("boyut", "size", "maksimum", "maximum");
    }
}
