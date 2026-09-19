package com.signedcontract.client;

import com.signedcontract.model.*;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class AiClient {
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;

    public AiClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** Package-private constructor for testing (allows injecting a mock HttpClient). */
    AiClient(String baseUrl, String apiKey, HttpClient http) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.http = http;
    }

    public AiCompareResult compare(String signedContent, String originalContent,
                                   LanguageAnalysis languageAnalysis) throws ContractAnalysisException {
        JSONObject body = new JSONObject();
        body.put("clause_number", "");
        body.put("signed_content", signedContent != null ? signedContent : "");
        body.put("original_content", originalContent != null ? originalContent : "");
        if (languageAnalysis != null) {
            body.put("language_hint", languageAnalysis.activeLanguage());
            body.put("multilingual_note", languageAnalysis.multilingual() ? "multilingual document" : JSONObject.NULL);
        }
        String resp = postWithRetry(baseUrl + "/ai/compare", body.toString());
        JSONObject obj = new JSONObject(resp);
        Boolean changes = obj.isNull("changes") ? null : obj.getBoolean("changes");
        String result = obj.optString("result", "");
        return new AiCompareResult(changes, result);
    }

    private String postWithRetry(String url, String body) throws ContractAnalysisException {
        int[] delays = {1000, 2000, 4000};
        Exception last = null;
        for (int i = 0; i <= 3; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60)).build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 401)
                    throw new ContractAnalysisException("HTTP 401 Unauthorized: invalid API key");
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) return resp.body();
                if (i < 3 && (resp.statusCode() == 429 || resp.statusCode() >= 500)) {
                    Thread.sleep(delays[i]);
                    continue;
                }
                throw new ContractAnalysisException("HTTP " + resp.statusCode());
            } catch (ContractAnalysisException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (i < 3) {
                    try { Thread.sleep(delays[i]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw new ContractAnalysisException("Request failed: " + (last != null ? last.getMessage() : ""), last);
    }
}
