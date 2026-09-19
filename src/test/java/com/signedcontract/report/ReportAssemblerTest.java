package com.signedcontract.report;

import com.signedcontract.model.*;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportAssemblerTest {

    private final ReportAssembler assembler = new ReportAssembler();

    @Test
    void nullSignedImage_becomesJsonNull() {
        SignedContract sc = new SignedContract(
                "Test Sözleşme",
                Map.of("1", new ClauseContent("## 1. Madde", null)),
                List.of(), List.of(), null);

        JSONObject json = assembler.toSignedContractJson(sc);
        JSONObject clause1 = json.getJSONObject("clauses").getJSONObject("1");

        assertThat(clause1.isNull("image")).isTrue();
    }

    @Test
    void analysisJson_naturalOrdering() {
        SignedContract sc = new SignedContract(
                "T",
                Map.of("2", new ClauseContent("madde 2", null),
                       "10", new ClauseContent("madde 10", null),
                       "1", new ClauseContent("madde 1", null)),
                List.of(), List.of(), null);
        OriginalContract oc = new OriginalContract("T", Map.of(), List.of());

        PipelineResult result = assembler.assemble(sc, oc, Map.of(), Map.of(), null, List.of(), List.of());
        JSONObject analyzeClauses = result.analysisJson().getJSONObject("analyze_clauses");

        List<String> keys = List.copyOf(analyzeClauses.keySet());
        assertThat(keys).containsExactly("1", "2", "10");
    }

    @Test
    void aiCompareNullChanges_mapsToJsonNull() {
        SignedContract sc = new SignedContract("T",
                Map.of("1", new ClauseContent("x", null)), List.of(), List.of(), null);
        OriginalContract oc = new OriginalContract("T", Map.of(), List.of());

        Map<String, AiCompareResult> ai = Map.of("1", new AiCompareResult(null, "AI service error"));
        PipelineResult result = assembler.assemble(sc, oc, Map.of(), ai, null, List.of(), List.of());

        JSONObject entry = result.analysisJson().getJSONObject("analyze_clauses").getJSONObject("1");
        assertThat(entry.getJSONObject("diff_ai").isNull("changes")).isTrue();
    }

    @Test
    void additionalSections_mappedWithTypeAndContent() {
        OriginalContract oc = new OriginalContract("T", Map.of(),
                List.of(new AdditionalSection("protocol", "## Ek Protokol 1")));

        JSONObject json = assembler.toOriginalContractJson(oc);
        assertThat(json.getJSONArray("additional").getJSONObject(0).getString("type")).isEqualTo("protocol");
    }

    @Test
    void resultJson_combinesAllThreeSections() {
        SignedContract sc = new SignedContract("T", Map.of(), List.of(), List.of(), null);
        OriginalContract oc = new OriginalContract("T", Map.of(), List.of());
        JSONObject analysis = new JSONObject().put("analyze_clauses", new JSONObject());

        JSONObject result = assembler.toResultJson(sc, oc, analysis);

        assertThat(result.has("signed_contract")).isTrue();
        assertThat(result.has("original_contract")).isTrue();
        assertThat(result.has("analysis")).isTrue();
    }
}
