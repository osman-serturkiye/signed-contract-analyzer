package com.signedcontract.processor;

import com.signedcontract.model.ContractAnalysisException;
import com.signedcontract.model.PageImage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PDFProcessor {

    private static final int DEFAULT_DPI = 300;
    private static final int MAX_PAGES = 500;
    private static final long DEFAULT_MAX_FILE_SIZE_MB = 100;
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46}; // %PDF

    private final long maxFileSizeMb;

    public PDFProcessor() { this.maxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB; }
    public PDFProcessor(long maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }

    public void validate(String pdfPath) throws ContractAnalysisException {
        if (pdfPath == null) throw new IllegalArgumentException("PDF path must not be null");
        File file = new File(pdfPath);
        if (!file.exists() || file.isDirectory())
            throw new IllegalArgumentException("File does not exist: " + pdfPath);

        // Magic byte check
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) < 4 || magic[0] != 0x25 || magic[1] != 0x50 || magic[2] != 0x44 || magic[3] != 0x46)
                throw new ContractAnalysisException("Invalid file format: only PDF accepted");
        } catch (ContractAnalysisException e) {
            throw e;
        } catch (IOException e) {
            throw new ContractAnalysisException("Cannot read file: " + e.getMessage(), e);
        }

        // File size check
        if (file.length() > maxFileSizeMb * 1024L * 1024L)
            throw new ContractAnalysisException(
                "File size exceeds maximum allowed size of " + maxFileSizeMb + " MB");

        // PDF structure check (encryption, page count)
        try (PDDocument doc = PDDocument.load(file)) {
            if (doc.isEncrypted())
                throw new ContractAnalysisException("PDF is encrypted and cannot be processed");
            int pages = doc.getNumberOfPages();
            if (pages < 1 || pages > MAX_PAGES)
                throw new ContractAnalysisException(
                    "PDF page count " + pages + " is outside the supported range (1-" + MAX_PAGES + ")");
        } catch (ContractAnalysisException e) {
            throw e;
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new ContractAnalysisException("PDF is password-protected and cannot be processed", e);
        } catch (IOException e) {
            throw new ContractAnalysisException("PDF file is corrupted or cannot be read: " + e.getMessage(), e);
        }
    }

    public List<PageImage> convertToPageImages(String pdfPath) throws ContractAnalysisException {
        validate(pdfPath);
        File file = new File(pdfPath);
        List<PageImage> result = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(file)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, DEFAULT_DPI, ImageType.RGB);
                result.add(new PageImage(i + 1, img, img.getWidth(), img.getHeight()));
            }
        } catch (IOException e) {
            throw new ContractAnalysisException("Failed to render PDF pages: " + e.getMessage(), e);
        }
        return result;
    }
}
