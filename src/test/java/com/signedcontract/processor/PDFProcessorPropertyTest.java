package com.signedcontract.processor;

import com.signedcontract.model.ContractAnalysisException;
import com.signedcontract.model.PageImage;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for {@link PDFProcessor}.
 *
 * <p><b>Validates: Requirements 1.1, 1.3, 1.5, 1.6, 22.1, 22.2</b>
 *
 * <p>Property 1 (Req 1.1, 1.3 — Task 4.2): Any file whose first four bytes are not the
 * PDF magic bytes {@code %PDF} must be rejected by {@link PDFProcessor#validate(String)}.
 *
 * <p>Property 2 (Req 22.1, 22.2 — Task 4.4): A file that exceeds the configured
 * maximum size limit must always be rejected with a size-related error message.
 *
 * <p>Property 2 (Req 1.5, 1.6 — Task 4.3): For any valid multi-page PDF, every rendered
 * {@link PageImage} must have pixel dimensions consistent with 300 DPI rendering.
 */
class PDFProcessorPropertyTest {

    private static final int DPI = 300;
    // Tolerance in pixels for floating-point rounding during DPI conversion
    private static final int PIXEL_TOLERANCE = 2;

    /**
     * Property: any file whose leading bytes do not match the PDF magic header
     * {@code %PDF} (0x25 0x50 0x44 0x46) must always be rejected by
     * {@link PDFProcessor#validate(String)}, throwing either
     * {@link ContractAnalysisException} or {@link IllegalArgumentException}.
     *
     * <b>Validates: Requirements 1.1, 1.3</b>
     */
    @Property
    void nonPdfMagicBytesAlwaysRejected(
            @ForAll @Size(4) byte[] prefix) throws Exception {
        Assume.that(
            prefix.length < 4 ||
            prefix[0] != 0x25 || prefix[1] != 0x50 ||
            prefix[2] != 0x44 || prefix[3] != 0x46
        );

        File tmp = File.createTempFile("nonpdf", ".bin");
        tmp.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(prefix);
        }

        PDFProcessor processor = new PDFProcessor();
        assertThatThrownBy(() -> processor.validate(tmp.getAbsolutePath()))
            .isInstanceOfAny(ContractAnalysisException.class, IllegalArgumentException.class);
    }

    /**
     * Property: a file that starts with valid PDF magic bytes but whose total size
     * exceeds the configured {@code maxFileSizeMb} limit must always be rejected
     * with a {@link ContractAnalysisException} whose message mentions "size".
     *
     * <b>Validates: Requirements 22.1, 22.2</b>
     */
    @Property
    void fileSizeExceedingLimitAlwaysRejected(
            @ForAll @IntRange(min = 1, max = 5) int maxMb) throws Exception {
        long limitBytes = (long) maxMb * 1024 * 1024;

        File tmp = File.createTempFile("bigfile", ".pdf");
        tmp.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(new byte[]{0x25, 0x50, 0x44, 0x46}); // %PDF magic header
            // Write just enough padding to exceed the limit without allocating too much memory
            byte[] padding = new byte[Math.min((int) (limitBytes + 1), 6 * 1024 * 1024)];
            fos.write(padding);
        }

        PDFProcessor processor = new PDFProcessor(maxMb);
        assertThatThrownBy(() -> processor.validate(tmp.getAbsolutePath()))
            .isInstanceOf(ContractAnalysisException.class)
            .hasMessageContaining("size");
    }

    /**
     * Property 2: Sayfa Görüntüsü 300 DPI Garantisi.
     *
     * <p>For any valid PDF with a page count in the range [1, 10], every
     * {@link PageImage} returned by {@link PDFProcessor#convertToPageImages(String)}
     * must have pixel dimensions consistent with 300 DPI rendering of the
     * underlying PDF page size.
     *
     * <p>The invariant verified is:
     * <pre>
     *   expectedWidthPx  = round(pageWidthInPoints  / 72.0 * 300)
     *   expectedHeightPx = round(pageHeightInPoints / 72.0 * 300)
     *   |image.widthPx  - expectedWidthPx|  <= PIXEL_TOLERANCE
     *   |image.heightPx - expectedHeightPx| <= PIXEL_TOLERANCE
     * </pre>
     *
     * <p>PDFBox uses 72 points per inch internally; a 300 DPI render therefore
     * scales each page dimension by {@code 300 / 72}.
     *
     * <p><b>Validates: Requirements 1.5, 1.6</b>
     */
    @Property(tries = 10)
    @Label("Property 2: Sayfa Görüntüsü 300 DPI Garantisi")
    void pageImagesAre300Dpi(
            @ForAll @IntRange(min = 1, max = 10) int pageCount) throws Exception {

        // Build a minimal, renderable PDF with the requested number of A4 pages
        File tmp = createMultiPagePdf(pageCount, PDRectangle.A4);
        try {
            PDFProcessor processor = new PDFProcessor();
            List<PageImage> pages = processor.convertToPageImages(tmp.getAbsolutePath());

            // Invariant 1: returned list must contain exactly pageCount images
            assertThat(pages)
                .as("Page count must match number of pages in the PDF")
                .hasSize(pageCount);

            // Compute expected pixel dimensions for A4 at 300 DPI.
            // PDFBox defines A4 as 595.275 x 841.89 points (1 point = 1/72 inch).
            float pageWidthPt  = PDRectangle.A4.getWidth();
            float pageHeightPt = PDRectangle.A4.getHeight();
            int expectedWidthPx  = Math.round(pageWidthPt  / 72.0f * DPI);
            int expectedHeightPx = Math.round(pageHeightPt / 72.0f * DPI);

            for (int i = 0; i < pages.size(); i++) {
                PageImage img = pages.get(i);

                // Invariant 2: page number is 1-based and sequential
                assertThat(img.pageNumber())
                    .as("Page %d must have 1-based sequential page number", i + 1)
                    .isEqualTo(i + 1);

                // Invariant 3: width matches 300 DPI within tolerance
                assertThat(img.widthPx())
                    .as("Page %d width (%d px) must be within %d px of expected 300 DPI width (%d px)",
                        i + 1, img.widthPx(), PIXEL_TOLERANCE, expectedWidthPx)
                    .isBetween(expectedWidthPx - PIXEL_TOLERANCE, expectedWidthPx + PIXEL_TOLERANCE);

                // Invariant 4: height matches 300 DPI within tolerance
                assertThat(img.heightPx())
                    .as("Page %d height (%d px) must be within %d px of expected 300 DPI height (%d px)",
                        i + 1, img.heightPx(), PIXEL_TOLERANCE, expectedHeightPx)
                    .isBetween(expectedHeightPx - PIXEL_TOLERANCE, expectedHeightPx + PIXEL_TOLERANCE);

                // Invariant 5: the embedded BufferedImage dimensions match the reported values
                assertThat(img.image())
                    .as("Page %d: embedded BufferedImage must not be null", i + 1)
                    .isNotNull();
                assertThat(img.image().getWidth())
                    .as("Page %d: BufferedImage width must match PageImage.widthPx()", i + 1)
                    .isEqualTo(img.widthPx());
                assertThat(img.image().getHeight())
                    .as("Page %d: BufferedImage height must match PageImage.heightPx()", i + 1)
                    .isEqualTo(img.heightPx());
            }
        } finally {
            tmp.delete();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a minimal, valid, renderable PDF file with {@code pageCount} blank
     * pages, each of the given {@code pageSize}, saved to a temporary file.
     */
    private static File createMultiPagePdf(int pageCount, PDRectangle pageSize) throws IOException {
        File tmp = File.createTempFile("dpi_test_", ".pdf");
        tmp.deleteOnExit();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                doc.addPage(new PDPage(pageSize));
            }
            doc.save(tmp);
        }
        return tmp;
    }
}
