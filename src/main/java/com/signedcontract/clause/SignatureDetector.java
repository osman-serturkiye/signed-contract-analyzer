package com.signedcontract.clause;

import com.signedcontract.client.ImageClient;
import com.signedcontract.model.*;
import com.signedcontract.warning.WarningCollector;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Detects blocks of {@code type: "signature"} in page-level OCR output,
 * crops the signature image via the Python {@code /image/crop} endpoint,
 * and attempts to read the signer's name from nearby text blocks.
 *
 * <p>Validates: Requirements 6.1–6.5</p>
 */
public class SignatureDetector {

    /** Vertical window (pixels) around a signature block searched for a name. */
    private static final int NAME_SEARCH_RADIUS_PX = 150;

    private final ImageClient imageClient;
    private final WarningCollector warnings;

    public SignatureDetector(ImageClient imageClient) {
        this(imageClient, null);
    }

    public SignatureDetector(ImageClient imageClient, WarningCollector warnings) {
        this.imageClient = imageClient;
        this.warnings = warnings;
    }

    public List<Signer> detect(List<PageImage> pageImages, List<PageOcrResult> ocrResults)
            throws ContractAnalysisException {
        List<Signer> signers = new ArrayList<>();

        for (PageOcrResult page : ocrResults) {
            PageImage image = findPageImage(pageImages, page.pageNumber());
            if (image == null) continue;

            String base64Page = encodeToBase64Jpeg(image);

            for (OcrBlock block : page.blocks()) {
                if (!"signature".equals(block.type())) continue;

                String croppedB64;
                try {
                    croppedB64 = imageClient.crop(base64Page, block.bbox(), 5, 2000, 0.85);
                } catch (ContractAnalysisException e) {
                    if (warnings != null) {
                        warnings.addWarning("SignatureDetector", Severity.WARN,
                                "İmza görüntüsü kırpılamadı (sayfa " + page.pageNumber() + "): " + e.getMessage());
                    }
                    continue;
                }

                String name = findNearbyName(page, block);
                if (name.isEmpty() && warnings != null) {
                    warnings.addWarning("SignatureDetector", Severity.WARN,
                            "İmzacı adı tespit edilemedi (sayfa " + page.pageNumber() + ").");
                }

                signers.add(new Signer(name, croppedB64));
            }
        }

        return signers;
    }

    private PageImage findPageImage(List<PageImage> pageImages, int pageNumber) {
        for (PageImage pi : pageImages) {
            if (pi.pageNumber() == pageNumber) return pi;
        }
        return null;
    }

    private String encodeToBase64Jpeg(PageImage pageImage) throws ContractAnalysisException {
        try {
            BufferedImage img = pageImage.image();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new ContractAnalysisException("Sayfa görüntüsü kodlanamadı: " + e.getMessage(), e);
        }
    }

    /**
     * Looks for a plausible person-name text block near the signature block
     * (same page, vertically close, non-numeric-only text).
     */
    private String findNearbyName(PageOcrResult page, OcrBlock signatureBlock) {
        int sigCenterY = signatureBlock.bbox().y() + signatureBlock.bbox().height() / 2;
        String best = "";
        int bestDistance = Integer.MAX_VALUE;

        for (OcrBlock candidate : page.blocks()) {
            if (candidate == signatureBlock) continue;
            if (!"text".equals(candidate.type()) && !"title".equals(candidate.type())) continue;
            String text = candidate.content() == null ? "" : candidate.content().trim();
            if (text.isEmpty() || looksLikeClauseNumber(text)) continue;

            int candCenterY = candidate.bbox().y() + candidate.bbox().height() / 2;
            int distance = Math.abs(candCenterY - sigCenterY);
            if (distance <= NAME_SEARCH_RADIUS_PX && distance < bestDistance) {
                bestDistance = distance;
                best = text;
            }
        }
        return best;
    }

    private static final Pattern CLAUSE_LIKE = Pattern.compile("^\\d+(\\.\\d+)*\\.?\\s");

    private boolean looksLikeClauseNumber(String text) {
        Matcher m = CLAUSE_LIKE.matcher(text);
        return m.find();
    }
}
