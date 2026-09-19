package com.signedcontract.model;

public record AiCompareResult(
        Boolean changes,  // nullable — null indicates service access error
        String result
) {}
