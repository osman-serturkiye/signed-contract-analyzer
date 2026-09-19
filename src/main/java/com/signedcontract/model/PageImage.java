package com.signedcontract.model;

import java.awt.image.BufferedImage;

public record PageImage(
        int pageNumber,
        BufferedImage image,
        int widthPx,
        int heightPx
) {}
