package com.signedcontract.client;

import com.signedcontract.model.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;

public class OcrClient {
    private final String baseUrl;
    private final String apiKey;
    private final String adapter;
    private final HttpClient http;

    public OcrClient(String baseUrl, String apiKey, String adapter) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.adapter = adapter.toLowerCase();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public PageOcrResult performOcr(PageImage pageImage, String language) throws ContractAnalysisException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            ImageIO.write(pageImage.image(), "jpg", bos);
        } catch (Exception e) {
            throw new ContractAnalysisException("Failed to encode image: " + e.getMessage(), e);
        }
        String b64 = Base64.getEncoder().encodeToString(bos.toByteArray());

        JSONObject body = new JSONObject();
        body.put("image", b64);
        body.put("language", language != null ? language : "tr");
        body.put("config", new JSONObject());

        String responseBody = postWithRetry(baseUrl + "/ocr/" + adapter, body.toString());
        JSONObject resp = new JSONObject(responseBody);
        JSONArray blocksArr = resp.getJSONArray("blocks");
        List<OcrBlock> blocks = new ArrayList<>();
        for (int i = 0; i < blocksArr.length(); i++) {
            JSONObject b = blocksArr.getJSONObject(i);
            JSONObject bboxJ = b.getJSONObject("bbox");
            BoundingBox bbox = new BoundingBox(
                bboxJ.getInt("x"), bboxJ.getInt("y"),
                bboxJ.getInt("width"), bboxJ.getInt("height"));
            Double conf = b.isNull("confidence") ? null : b.getDouble("confidence");
            blocks.add(new OcrBlock(b.getString("type"), bbox, b.getString("content"), conf));
        }
        return new PageOcrResult(pageImage.pageNumber(), blocks,
            resp.getInt("page_width"), resp.getInt("page_height"));
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
                throw new ContractAnalysisException("HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (ContractAnalysisException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (i < 3) {
                    try { Thread.sleep(delays[i]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw new ContractAnalysisException("Request failed after retries: " + (last != null ? last.getMessage() : ""), last);
    }
}
