package com.signedcontract.model;

import org.json.JSONObject;
import java.util.List;

public record PipelineResult(
        SignedContract signedContract,
        OriginalContract originalContract,
        JSONObject analysisJson,
        List<AnalysisWarning> warnings
) {}
