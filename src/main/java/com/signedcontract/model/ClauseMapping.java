package com.signedcontract.model;

import java.util.List;

public record ClauseMapping(
        String clauseNumber,
        List<Integer> pageNumbers,
        List<BoundingBox> bboxPerPage,
        List<OcrBlock> blocks,
        String markdownContent
) {}
