package com.signedcontract.model;

import org.json.JSONObject;

public record PipelineConfig(
        String ocrAdapter,
        JSONObject ocrConfig,
        String aiAdapter,
        JSONObject aiConfig,
        int bboxMargin,
        String pythonServiceUrl,
        String pythonServiceApiKey,
        int aiMaxConcurrency,
        int ocrMaxConcurrency,
        long retentionSeconds,
        long maxFileSizeMb,
        int maxResultSizeMb,
        String primaryLanguage,
        String logFilePath,
        String logFormat
) {}
