package com.signedcontract.model;

public record ColumnInfo(
        int index,
        String language,
        ColumnBBox bbox
) {}
