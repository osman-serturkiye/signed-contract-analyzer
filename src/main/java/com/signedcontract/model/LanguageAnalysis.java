package com.signedcontract.model;

import java.util.List;

public record LanguageAnalysis(
        boolean multilingual,
        String activeLanguage,
        List<ColumnInfo> columns
) {}
