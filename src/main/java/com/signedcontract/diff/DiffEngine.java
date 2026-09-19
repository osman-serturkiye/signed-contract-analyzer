package com.signedcontract.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.signedcontract.model.DiffResult;
import com.signedcontract.ratelimit.RateLimiter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Computes line-level text diffs between the OCR'd signed-contract Markdown
 * and the original DOCX Markdown, using java-diff-utils.
 *
 * <p>Validates: Requirements 10.1–10.6</p>
 */
public class DiffEngine {

    /**
     * Diffs two Markdown strings and returns a {@link DiffResult}.
     *
     * <p>Identical content (Property 7) always yields {@code changes=false, result=""}.
     * Differing content (Property 8) always yields {@code changes=true}.</p>
     */
    public DiffResult diff(String signedMarkdown, String originalMarkdown) {
        String s = signedMarkdown == null ? "" : signedMarkdown;
        String o = originalMarkdown == null ? "" : originalMarkdown;

        if (s.equals(o)) {
            return new DiffResult(false, "");
        }

        List<String> signedLines = List.of(s.split("\n", -1));
        List<String> originalLines = List.of(o.split("\n", -1));

        Patch<String> patch = DiffUtils.diff(originalLines, signedLines);

        StringBuilder md = new StringBuilder();
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            for (String line : delta.getSource().getLines()) {
                md.append("- ~~").append(line).append("~~\n");
            }
            for (String line : delta.getTarget().getLines()) {
                md.append("+ **").append(line).append("**\n");
            }
        }

        return new DiffResult(true, md.toString().stripTrailing());
    }

    /**
     * Diffs every clause present in either {@code signedClauses} or
     * {@code originalClauses}, running at most {@code maxConcurrency} diffs
     * in parallel via {@link RateLimiter}.
     *
     * <p>A clause present only on one side is still diffed against an empty
     * string, so it is reported as changed (Req 10.5, 10.6 are surfaced by the
     * caller/ReportAssembler using clause-presence information).</p>
     */
    public Map<String, DiffResult> diffAll(
            Map<String, String> signedClauses,
            Map<String, String> originalClauses,
            int maxConcurrency) {

        Map<String, String> signed = signedClauses == null ? Map.of() : signedClauses;
        Map<String, String> original = originalClauses == null ? Map.of() : originalClauses;

        java.util.LinkedHashSet<String> allKeys = new java.util.LinkedHashSet<>();
        allKeys.addAll(signed.keySet());
        allKeys.addAll(original.keySet());

        RateLimiter limiter = new RateLimiter(Math.max(1, maxConcurrency));
        Map<String, CompletableFuture<DiffResult>> futures = new LinkedHashMap<>();
        for (String key : allKeys) {
            futures.put(key, limiter.submit(() ->
                    diff(signed.get(key), original.get(key))));
        }

        Map<String, DiffResult> results = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, CompletableFuture<DiffResult>> e : futures.entrySet()) {
                results.put(e.getKey(), e.getValue().join());
            }
        } finally {
            limiter.shutdown();
        }
        return results;
    }
}
