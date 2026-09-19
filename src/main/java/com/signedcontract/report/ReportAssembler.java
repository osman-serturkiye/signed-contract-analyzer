package com.signedcontract.report;

import com.signedcontract.model.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Combines pipeline outputs into the JSON structures required by
 * {@code ContractAnalyzer.getResultJson()} / {@code getSignedContractJson()} /
 * {@code getOriginalContractJson()} / {@code getAnalysisJson()}.
 *
 * <p>Validates: Requirements 7.1–7.5, 9.7, 12.1–12.6, 16.9–16.12</p>
 */
public class ReportAssembler {

    public PipelineResult assemble(
            SignedContract signedContract,
            OriginalContract originalContract,
            Map<String, DiffResult> diffs,
            Map<String, AiCompareResult> aiResults,
            LanguageAnalysis languageAnalysis,
            List<Signer> signers,
            List<AnalysisWarning> warnings) {

        JSONObject analysisJson = buildAnalysisJson(signedContract, originalContract, diffs, aiResults);

        return new PipelineResult(signedContract, originalContract, analysisJson, warnings);
    }

    // ── analyze_clauses ──────────────────────────────────────────────────────

    private JSONObject buildAnalysisJson(
            SignedContract signedContract,
            OriginalContract originalContract,
            Map<String, DiffResult> diffs,
            Map<String, AiCompareResult> aiResults) {

        Map<String, ClauseContent> signedClauses =
                signedContract != null && signedContract.clauses() != null
                        ? signedContract.clauses() : Map.of();
        Map<String, OriginalClauseContent> originalClauses =
                originalContract != null && originalContract.clauses() != null
                        ? originalContract.clauses() : Map.of();
        Map<String, DiffResult> diffMap = diffs != null ? diffs : Map.of();
        Map<String, AiCompareResult> aiMap = aiResults != null ? aiResults : Map.of();

        LinkedHashSet<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(signedClauses.keySet());
        allKeys.addAll(originalClauses.keySet());
        allKeys.addAll(diffMap.keySet());
        allKeys.addAll(aiMap.keySet());

        List<String> sortedKeys = new ArrayList<>(allKeys);
        sortedKeys.sort(new NaturalClauseKeyComparator());

        JSONObject analyzeClauses = new JSONObject();
        for (String key : sortedKeys) {
            JSONObject entry = new JSONObject();

            ClauseContent signed = signedClauses.get(key);
            OriginalClauseContent original = originalClauses.get(key);

            entry.put("signed_image", signed != null && signed.image() != null ? signed.image() : JSONObject.NULL);
            entry.put("signed_content", signed != null && signed.content() != null ? signed.content() : "");
            entry.put("original_content", original != null && original.content() != null ? original.content() : "");

            DiffResult diff = diffMap.get(key);
            JSONObject diffJson = new JSONObject();
            diffJson.put("changes", diff != null && diff.changes());
            diffJson.put("result", diff != null && diff.result() != null ? diff.result() : "");
            entry.put("diff", diffJson);

            AiCompareResult ai = aiMap.get(key);
            JSONObject diffAiJson = new JSONObject();
            diffAiJson.put("changes", ai != null && ai.changes() != null ? ai.changes() : JSONObject.NULL);
            diffAiJson.put("result", ai != null && ai.result() != null ? ai.result() : "");
            entry.put("diff_ai", diffAiJson);

            analyzeClauses.put(key, entry);
        }

        JSONObject result = new JSONObject();
        result.put("analyze_clauses", analyzeClauses);
        return result;
    }

    // ── JSON views used by ContractAnalyzer ─────────────────────────────────

    public JSONObject toSignedContractJson(SignedContract sc) {
        JSONObject json = new JSONObject();
        json.put("title", sc.title() != null ? sc.title() : "");

        JSONObject clauses = new JSONObject();
        List<String> keys = new ArrayList<>(sc.clauses() != null ? sc.clauses().keySet() : Set.of());
        keys.sort(new NaturalClauseKeyComparator());
        for (String key : keys) {
            ClauseContent c = sc.clauses().get(key);
            JSONObject cj = new JSONObject();
            cj.put("content", c.content() != null ? c.content() : "");
            cj.put("image", c.image() != null ? c.image() : JSONObject.NULL);
            clauses.put(key, cj);
        }
        json.put("clauses", clauses);

        JSONArray signersArr = new JSONArray();
        if (sc.signers() != null) {
            for (Signer s : sc.signers()) {
                JSONObject sj = new JSONObject();
                sj.put("name", s.name() != null ? s.name() : "");
                sj.put("image", s.base64Image() != null ? s.base64Image() : "");
                signersArr.put(sj);
            }
        }
        json.put("signers", signersArr);

        json.put("additional", additionalToJsonArray(sc.additional()));

        if (sc.languageAnalysis() != null) {
            json.put("language_analysis", languageAnalysisToJson(sc.languageAnalysis()));
        }

        return json;
    }

    public JSONObject toOriginalContractJson(OriginalContract oc) {
        JSONObject json = new JSONObject();
        json.put("title", oc.title() != null ? oc.title() : "");

        JSONObject clauses = new JSONObject();
        List<String> keys = new ArrayList<>(oc.clauses() != null ? oc.clauses().keySet() : Set.of());
        keys.sort(new NaturalClauseKeyComparator());
        for (String key : keys) {
            OriginalClauseContent c = oc.clauses().get(key);
            JSONObject cj = new JSONObject();
            cj.put("content", c.content() != null ? c.content() : "");
            clauses.put(key, cj);
        }
        json.put("clauses", clauses);
        json.put("additional", additionalToJsonArray(oc.additional()));
        return json;
    }

    private JSONArray additionalToJsonArray(List<AdditionalSection> sections) {
        JSONArray arr = new JSONArray();
        if (sections != null) {
            for (AdditionalSection s : sections) {
                JSONObject sj = new JSONObject();
                sj.put("type", s.type() != null ? s.type() : "additional");
                sj.put("content", s.content() != null ? s.content() : "");
                arr.put(sj);
            }
        }
        return arr;
    }

    private JSONObject languageAnalysisToJson(LanguageAnalysis la) {
        JSONObject json = new JSONObject();
        json.put("multilingual", la.multilingual());
        json.put("active_language", la.activeLanguage() != null ? la.activeLanguage() : JSONObject.NULL);
        JSONArray cols = new JSONArray();
        if (la.columns() != null) {
            for (ColumnInfo c : la.columns()) {
                JSONObject cj = new JSONObject();
                cj.put("index", c.index());
                cj.put("language", c.language());
                JSONObject bbox = new JSONObject();
                bbox.put("x_start", c.bbox().xStart());
                bbox.put("x_end", c.bbox().xEnd());
                bbox.put("y_start", c.bbox().yStart());
                bbox.put("y_end", c.bbox().yEnd());
                cj.put("bbox", bbox);
                cols.put(cj);
            }
        }
        json.put("columns", cols);
        return json;
    }

    public JSONObject toResultJson(SignedContract sc, OriginalContract oc, JSONObject analysisJson) {
        JSONObject result = new JSONObject();
        result.put("signed_contract", toSignedContractJson(sc));
        result.put("original_contract", toOriginalContractJson(oc));
        result.put("analysis", analysisJson != null ? analysisJson : new JSONObject());
        return result;
    }

    // ── Natural clause ordering ──────────────────────────────────────────────

    /**
     * Orders clause keys like "1" &lt; "1.1" &lt; "1.2" &lt; "2" &lt; "10",
     * placing a {@code "N-duplicate-K"} key immediately after its base key
     * "N" (Req 29.3 / Property 9, 13).
     */
    static class NaturalClauseKeyComparator implements Comparator<String> {
        @Override
        public int compare(String a, String b) {
            String[] baseA = splitDuplicateSuffix(a);
            String[] baseB = splitDuplicateSuffix(b);

            int cmp = compareNumericParts(baseA[0], baseB[0]);
            if (cmp != 0) return cmp;

            int dupA = baseA[1] == null ? 0 : Integer.parseInt(baseA[1]);
            int dupB = baseB[1] == null ? 0 : Integer.parseInt(baseB[1]);
            return Integer.compare(dupA, dupB);
        }

        private String[] splitDuplicateSuffix(String key) {
            int idx = key.indexOf("-duplicate-");
            if (idx < 0) return new String[]{key, null};
            return new String[]{key.substring(0, idx), key.substring(idx + "-duplicate-".length())};
        }

        private int compareNumericParts(String a, String b) {
            String[] partsA = a.split("\\.");
            String[] partsB = b.split("\\.");
            int len = Math.max(partsA.length, partsB.length);
            for (int i = 0; i < len; i++) {
                long va = i < partsA.length ? parseLongSafe(partsA[i]) : -1;
                long vb = i < partsB.length ? parseLongSafe(partsB[i]) : -1;
                if (va != vb) return Long.compare(va, vb);
            }
            return 0;
        }

        private long parseLongSafe(String s) {
            try {
                return Long.parseLong(s.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
