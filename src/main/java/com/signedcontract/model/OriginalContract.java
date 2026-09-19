package com.signedcontract.model;

import java.util.List;
import java.util.Map;

public record OriginalContract(
        String title,
        Map<String, OriginalClauseContent> clauses,
        List<AdditionalSection> additional
) {}
