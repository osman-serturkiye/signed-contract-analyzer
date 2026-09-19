package com.signedcontract.model;

import java.util.List;
import java.util.Map;

public record SignedContract(
        String title,
        Map<String, ClauseContent> clauses,
        List<Signer> signers,
        List<AdditionalSection> additional,
        LanguageAnalysis languageAnalysis
) {}
