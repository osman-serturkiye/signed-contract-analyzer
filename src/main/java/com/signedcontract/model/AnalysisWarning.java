package com.signedcontract.model;

public record AnalysisWarning(
        String component,
        Severity severity,
        String message
) {}
