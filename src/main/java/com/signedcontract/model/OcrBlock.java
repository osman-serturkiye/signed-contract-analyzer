package com.signedcontract.model;

public record OcrBlock(
        String type,
        BoundingBox bbox,
        String content,
        Double confidence
) {}
