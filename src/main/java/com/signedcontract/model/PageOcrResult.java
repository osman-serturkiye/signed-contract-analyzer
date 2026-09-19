package com.signedcontract.model;

import java.util.List;

public record PageOcrResult(
        int pageNumber,
        List<OcrBlock> blocks,
        int pageWidthPx,
        int pageHeightPx
) {}
