package com.signedcontract.client;

import com.signedcontract.model.ContractAnalysisException;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class ReportClient {
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;

    public ReportClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public String generateHtml(JSONObject resultJson) throws ContractAnalysisException {
        return postStringWithRetry(baseUrl + "/report/html", resultJson.toString());
    }

    public byte[] generatePdf(JSONObject resultJson) throws ContractAnalysisException {
        return postBytesWithRetry(baseUrl + "/report/pdf", resultJson.toString());
    }

    private String postStringWithRetry(String url, String body) throws ContractAnalysisException {
        int[] d = {1000, 2000, 4000};
        Exception last = null;
        for (int i = 0; i <= 3; i++) {
            try {
                HttpResponse<String> resp = http.send(build(url, body), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 401)
                    throw new ContractAnalysisException("HTTP 401 Unauthorized: invalid API key");
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) return resp.body();
                if (i < 3 && (resp.statusCode() == 429 || resp.statusCode() >= 500)) {
                    Thread.sleep(d[i]);
                    continue;
                }
                throw new ContractAnalysisException("HTTP " + resp.statusCode());
            } catch (ContractAnalysisException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (i < 3) {
                    try { Thread.sleep(d[i]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw new ContractAnalysisException("Request failed: " + (last != null ? last.getMessage() : ""), last);
    }

    private byte[] postBytesWithRetry(String url, String body) throws ContractAnalysisException {
        int[] d = {1000, 2000, 4000};
        Exception last = null;
        for (int i = 0; i <= 3; i++) {
            try {
                HttpResponse<byte[]> resp = http.send(build(url, body), HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 401)
                    throw new ContractAnalysisException("HTTP 401 Unauthorized: invalid API key");
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) return resp.body();
                if (i < 3 && (resp.statusCode() == 429 || resp.statusCode() >= 500)) {
                    Thread.sleep(d[i]);
                    continue;
                }
                throw new ContractAnalysisException("HTTP " + resp.statusCode());
            } catch (ContractAnalysisException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (i < 3) {
                    try { Thread.sleep(d[i]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw new ContractAnalysisException("Request failed: " + (last != null ? last.getMessage() : ""), last);
    }

    private HttpRequest build(String url, String body) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(120)).build();
    }
}
