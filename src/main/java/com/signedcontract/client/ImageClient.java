package com.signedcontract.client;

import com.signedcontract.model.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

public class ImageClient {
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;

    public ImageClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** Package-private constructor for testing (allows injecting a mock HttpClient). */
    ImageClient(String baseUrl, String apiKey, HttpClient http) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.http = http;
    }

    public String crop(String base64Image, BoundingBox bbox, int margin,
                       Integer maxDimensionPx, Double compressionQuality) throws ContractAnalysisException {
        JSONObject b = new JSONObject();
        b.put("image", base64Image);
        JSONObject bboxJ = new JSONObject();
        bboxJ.put("x", bbox.x());
        bboxJ.put("y", bbox.y());
        bboxJ.put("width", bbox.width());
        bboxJ.put("height", bbox.height());
        b.put("bbox", bboxJ);
        b.put("margin", margin);
        if (maxDimensionPx != null) b.put("max_dimension_px", maxDimensionPx);
        if (compressionQuality != null) b.put("compression_quality", compressionQuality);
        return new JSONObject(postWithRetry(baseUrl + "/image/crop", b.toString())).getString("image");
    }

    public String stitch(List<String> base64Images, int gapPx) throws ContractAnalysisException {
        JSONObject b = new JSONObject();
        b.put("images", new JSONArray(base64Images));
        b.put("direction", "vertical");
        b.put("gap_px", gapPx);
        return new JSONObject(postWithRetry(baseUrl + "/image/stitch", b.toString())).getString("image");
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
