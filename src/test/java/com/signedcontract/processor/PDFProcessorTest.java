package com.signedcontract.processor;

import com.signedcontract.model.ContractAnalysisException;
import com.signedcontract.model.PageImage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PDFProcessor}.
 *
 * <p>Covers Requirements 1.1–1.6:
 * <ul>
 *   <li>1.1 – System accepts only PDF format</li>
 *   <li>1.2 – PDF_İşleyici validates the file is a valid PDF</li>
 *   <li>1.3 – Non-PDF files produce "Invalid file format" error message</li>
 *   <li>1.4 – Corrupted/unreadable PDF produces a corruption error</li>
 *   <li>1.5 – Valid PDF is converted to images at 300 DPI</li>
 *   <li>1.6 – Multi-page PDFs (1–500 pages) are supported</li>
 * </ul>
 */
class PDFProcessorTest {

    @TempDir
    Path tempDir;

    private final PDFProcessor processor = new PDFProcessor();

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a minimal, valid, single-page PDF and returns the File. */
    private File createMinimalPdf() throws Exception {
        return createMultiPagePdf(1);
    }

    /** Creates a minimal, valid PDF with the given page count. */
    private File createMultiPagePdf(int pages) throws Exception {
        File f = tempDir.resolve("test_" + pages + "p.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(f);
        }
        return f;
    }

    /** Creates a valid PDF with pages of the given PDRectangle size. */
    private File createPdfWithPageSize(PDRectangle pageSize) throws Exception {
        File f = tempDir.resolve("sized.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(pageSize));
            doc.save(f);
        }
        return f;
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    /** Req 1.2 – null path must be refused immediately (pre-condition guard). */
    @Test
    void validate_nullPath_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> processor.validate(null));
    }

    /** Req 1.2 – a path that points to nothing must be refused. */
    @Test
    void validate_nonExistentFile_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> processor.validate(tempDir.resolve("ghost.pdf").toString()));
    }

    /** Req 1.2 – passing a directory path must be refused. */
    @Test
    void validate_directoryPath_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> processor.validate(tempDir.toString()));
    }

    // -------------------------------------------------------------------------
    // Format validation – magic bytes (Req 1.1, 1.3)
    // -------------------------------------------------------------------------

    /**
     * Req 1.1, 1.3 – a plain-text file has no PDF magic bytes; must be rejected
     * with the required "Invalid file format" message.
     */
    @Test
    void validate_nonPdfFile_throwsContractAnalysisExceptionWithFormatMessage() throws Exception {
        File f = tempDir.resolve("notpdf.txt").toFile();
        try (FileWriter w = new FileWriter(f)) {
            w.write("This is not a PDF file");
        }
        ContractAnalysisException ex = assertThrows(ContractAnalysisException.class,
                () -> processor.validate(f.getAbsolutePath()));
        assertTrue(ex.getMessage().toLowerCase().contains("invalid") ||
                   ex.getMessage().toLowerCase().contains("format"),
                "Error message should mention invalid format, got: " + ex.getMessage());
    }

    /**
     * Req 1.1, 1.3 – a binary file with wrong leading bytes must be rejected.
     */
    @Test
    void validate_wrongMagicBytes_throwsContractAnalysisException() throws Exception {
        File f = tempDir.resolve("wrong_magic.bin").toFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            // Start with 0x89 0x50 0x4E 0x47 (PNG magic) – not a PDF
            fos.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
            fos.write(new byte[100]);
        }
        assertThrows(ContractAnalysisException.class,
                () -> processor.validate(f.getAbsolutePath()));
    }

    /**
     * Req 1.1 – a file starting with %PDF magic bytes is accepted as a valid PDF
     * when its structure is also valid.
     */
    @Test
    void validate_validMinimalPdf_noException() throws Exception {
        assertDoesNotThrow(() -> processor.validate(createMinimalPdf().getAbsolutePath()));
    }

    // -------------------------------------------------------------------------
    // Password-protected / encrypted PDF (Req 1.4)
    // -------------------------------------------------------------------------

    /**
     * Req 1.2, 1.4 – a password-protected PDF must be rejected, since it cannot
     * be processed by the pipeline.
     */
    @Test
    void validate_passwordProtectedPdf_throwsContractAnalysisException() throws Exception {
        File f = tempDir.resolve("protected.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy policy = new StandardProtectionPolicy("ownerPass", "userPass", ap);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(f);
        }
        ContractAnalysisException ex = assertThrows(ContractAnalysisException.class,
                () -> processor.validate(f.getAbsolutePath()));
        // The message must clearly indicate encryption / password protection
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("encrypt") || msg.contains("password") || msg.contains("protected"),
                "Error message should mention encryption/password, got: " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // Corrupted PDF (Req 1.4)
    // -------------------------------------------------------------------------

    /**
     * Req 1.4 – a file that starts with the PDF magic bytes but whose structure
     * is completely invalid (corrupted) must produce a meaningful error.
     */
    @Test
    void validate_corruptedPdf_throwsContractAnalysisException() throws Exception {
        File f = tempDir.resolve("corrupt.pdf").toFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            // Valid PDF magic, then garbage – PDFBox cannot parse the structure
            fos.write(new byte[]{0x25, 0x50, 0x44, 0x46}); // %PDF
            fos.write("%%garbage content that is not a valid PDF structure".getBytes());
        }
        assertThrows(ContractAnalysisException.class,
                () -> processor.validate(f.getAbsolutePath()));
    }

    // -------------------------------------------------------------------------
    // File size limit
    // -------------------------------------------------------------------------

    /**
     * A file whose size exceeds the configured limit (even with PDF magic bytes)
     * must be rejected with a size-related error message.
     */
    @Test
    void validate_fileTooLarge_throwsContractAnalysisExceptionWithSizeMessage() throws Exception {
        File f = tempDir.resolve("big.pdf").toFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(new byte[]{0x25, 0x50, 0x44, 0x46}); // %PDF magic
            fos.write(new byte[2 * 1024 * 1024]);          // 2 MB padding
        }
        ContractAnalysisException ex = assertThrows(ContractAnalysisException.class,
                () -> new PDFProcessor(1).validate(f.getAbsolutePath()));
        assertTrue(ex.getMessage().toLowerCase().contains("size"),
                "Error message should mention size, got: " + ex.getMessage());
    }

    /**
     * A PDF exactly at the size limit must be accepted (boundary check).
     * We use a real small PDF well under 1 MB, which should pass the 1 MB limit.
     */
    @Test
    void validate_pdfExactlyAtSizeLimit_noException() throws Exception {
        File f = createMinimalPdf();
        // A minimal 1-page PDF is a few KB; a 1 MB limit should accept it
        assertDoesNotThrow(() -> new PDFProcessor(1).validate(f.getAbsolutePath()));
    }

    // -------------------------------------------------------------------------
    // Page count limits (Req 1.6)
    // -------------------------------------------------------------------------

    /**
     * Req 1.6 – a single-page PDF is within the 1–500 range and must be accepted.
     */
    @Test
    void validate_singlePagePdf_accepted() throws Exception {
        assertDoesNotThrow(() -> processor.validate(createMinimalPdf().getAbsolutePath()));
    }

    /**
     * Req 1.6 – a 500-page PDF is at the upper boundary and must be accepted.
     */
    @Test
    void validate_500PagePdf_accepted() throws Exception {
        assertDoesNotThrow(() -> processor.validate(createMultiPagePdf(500).getAbsolutePath()));
    }

    // -------------------------------------------------------------------------
    // 300 DPI conversion (Req 1.5)
    // -------------------------------------------------------------------------

    /**
     * Req 1.5 – converting a valid single-page PDF must return exactly one PageImage.
     */
    @Test
    void convertToPageImages_validOnePage_returnsOnePageImage() throws Exception {
        List<PageImage> pages = processor.convertToPageImages(createMinimalPdf().getAbsolutePath());
        assertEquals(1, pages.size());
        assertEquals(1, pages.get(0).pageNumber());
        assertTrue(pages.get(0).widthPx() > 0, "Width must be positive");
        assertTrue(pages.get(0).heightPx() > 0, "Height must be positive");
        assertNotNull(pages.get(0).image(), "Embedded BufferedImage must not be null");
    }

    /**
     * Req 1.5 – pixel dimensions of the rendered image must match 300 DPI.
     *
     * <p>PDFBox works in points (1 pt = 1/72 inch).
     * Expected pixel width  = round(widthPt  / 72 * 300).
     * Expected pixel height = round(heightPt / 72 * 300).
     * A tolerance of ±2 px accounts for floating-point rounding.
     */
    @Test
    void convertToPageImages_a4Page_pixelDimensionsMatch300Dpi() throws Exception {
        File f = createPdfWithPageSize(PDRectangle.A4);
        List<PageImage> pages = processor.convertToPageImages(f.getAbsolutePath());

        assertEquals(1, pages.size());
        PageImage img = pages.get(0);

        int expectedWidth  = Math.round(PDRectangle.A4.getWidth()  / 72.0f * 300);
        int expectedHeight = Math.round(PDRectangle.A4.getHeight() / 72.0f * 300);
        int tolerance = 2;

        assertTrue(Math.abs(img.widthPx() - expectedWidth) <= tolerance,
                String.format("Width %d px should be ~%d px at 300 DPI (±%d)", img.widthPx(), expectedWidth, tolerance));
        assertTrue(Math.abs(img.heightPx() - expectedHeight) <= tolerance,
                String.format("Height %d px should be ~%d px at 300 DPI (±%d)", img.heightPx(), expectedHeight, tolerance));
    }

    /**
     * Req 1.5 – reported widthPx / heightPx must match the actual BufferedImage dimensions.
     */
    @Test
    void convertToPageImages_pageImageDimensionsMatchBufferedImage() throws Exception {
        List<PageImage> pages = processor.convertToPageImages(
                createPdfWithPageSize(PDRectangle.LETTER).getAbsolutePath());

        assertFalse(pages.isEmpty());
        PageImage img = pages.get(0);

        assertEquals(img.widthPx(),  img.image().getWidth(),
                "PageImage.widthPx() must match BufferedImage.getWidth()");
        assertEquals(img.heightPx(), img.image().getHeight(),
                "PageImage.heightPx() must match BufferedImage.getHeight()");
    }

    // -------------------------------------------------------------------------
    // Multi-page support (Req 1.6)
    // -------------------------------------------------------------------------

    /**
     * Req 1.6 – a multi-page PDF must yield one PageImage per page, with sequential
     * 1-based page numbers.
     */
    @Test
    void convertToPageImages_multiPagePdf_returnsAllPagesInOrder() throws Exception {
        int pageCount = 5;
        List<PageImage> pages = processor.convertToPageImages(
                createMultiPagePdf(pageCount).getAbsolutePath());

        assertEquals(pageCount, pages.size(), "Must return exactly one image per PDF page");
        for (int i = 0; i < pageCount; i++) {
            assertEquals(i + 1, pages.get(i).pageNumber(),
                    "Page numbers must be 1-based and sequential");
        }
    }

    /**
     * Req 1.6 – a 10-page PDF must return 10 PageImages, each with positive dimensions.
     */
    @Test
    void convertToPageImages_tenPages_allImagesHavePositiveDimensions() throws Exception {
        List<PageImage> pages = processor.convertToPageImages(
                createMultiPagePdf(10).getAbsolutePath());

        assertEquals(10, pages.size());
        for (PageImage img : pages) {
            assertTrue(img.widthPx() > 0, "Width must be positive on every page");
            assertTrue(img.heightPx() > 0, "Height must be positive on every page");
            assertNotNull(img.image(), "BufferedImage must not be null on any page");
        }
    }

    /**
     * Req 1.6 – convertToPageImages must also call validate internally, so passing
     * a non-PDF file must throw ContractAnalysisException.
     */
    @Test
    void convertToPageImages_nonPdfFile_throwsContractAnalysisException() throws Exception {
        File f = tempDir.resolve("fake.pdf").toFile();
        try (FileWriter w = new FileWriter(f)) {
            w.write("Not a real PDF");
        }
        assertThrows(ContractAnalysisException.class,
                () -> processor.convertToPageImages(f.getAbsolutePath()));
    }
}
