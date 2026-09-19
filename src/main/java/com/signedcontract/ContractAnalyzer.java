package com.signedcontract;

import com.signedcontract.config.ConfigValidator;
import com.signedcontract.model.*;
import com.signedcontract.pipeline.AnalysisPipeline;
import com.signedcontract.report.FallbackReportGenerator;
import com.signedcontract.report.ReportAssembler;
import com.signedcontract.warning.WarningCollector;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Public API entry point. Fluent: {@code config().setSignedContractPdf()
 * .setOriginalContractDoc().start()} then {@code get*Json()} / {@code export*()}.
 *
 * <p>Validates: Requirements 16.1–16.14, 17.1, 17.8, 17.9, 18.1, 18.9, 30.1–30.6, 26.3</p>
 */
public class ContractAnalyzer {

    private enum State { IDLE, RUNNING, COMPLETED, FAILED }

    private final ConfigValidator configValidator = new ConfigValidator();
    private final ReportAssembler reportAssembler = new ReportAssembler();

    private JSONObject rawConfig;
    private PipelineConfig pipelineConfig;
    private String signedPdfPath;
    private String originalDocxPath;

    private State state = State.IDLE;
    private PipelineResult pipelineResult;
    private final WarningCollector warnings = new WarningCollector();

    public ContractAnalyzer() {
    }

    // ── Fluent configuration ────────────────────────────────────────────────

    public ContractAnalyzer config(JSONObject config) {
        configValidator.validate(config);
        this.rawConfig = config;
        this.pipelineConfig = toPipelineConfig(config);
        return this;
    }

    public ContractAnalyzer setSignedContractPdf(String filePath) {
        if (filePath == null || !new File(filePath).exists()) {
            throw new IllegalArgumentException("Signed contract PDF not found: " + filePath);
        }
        this.signedPdfPath = filePath;
        return this;
    }

    public ContractAnalyzer setOriginalContractDoc(String filePath) {
        if (filePath == null || !new File(filePath).exists()) {
            throw new IllegalArgumentException("Original contract DOCX not found: " + filePath);
        }
        this.originalDocxPath = filePath;
        return this;
    }

    // ── Execution ────────────────────────────────────────────────────────────

    public void start() throws ContractAnalysisException {
        if (state != State.IDLE) {
            throw new IllegalStateException(
                    "ContractAnalyzer.start() may only be called once per instance; current state: " + state);
        }
        if (pipelineConfig == null) {
            throw new IllegalStateException("config(JSONObject) must be called before start()");
        }
        if (signedPdfPath == null) {
            throw new IllegalStateException("setSignedContractPdf(...) must be called before start()");
        }
        if (originalDocxPath == null) {
            throw new IllegalStateException("setOriginalContractDoc(...) must be called before start()");
        }

        state = State.RUNNING;
        try {
            AnalysisPipeline pipeline = new AnalysisPipeline(pipelineConfig, warnings);
            pipelineResult = pipeline.execute(signedPdfPath, originalDocxPath);

            long resultSizeMb = estimateResultSizeMb(pipelineResult);
            int maxResultSizeMb = pipelineConfig.maxResultSizeMb() > 0 ? pipelineConfig.maxResultSizeMb() : 50;
            if (resultSizeMb > maxResultSizeMb) {
                warnings.addWarning("ContractAnalyzer", Severity.WARN,
                        "Sonuç JSON boyutu (" + resultSizeMb + " MB) MAX_RESULT_SIZE_MB (" + maxResultSizeMb + " MB) sınırını aşıyor.");
            }

            state = State.COMPLETED;
        } catch (ContractAnalysisException | RuntimeException e) {
            state = State.FAILED;
            throw e;
        }
    }

    // ── Result accessors ─────────────────────────────────────────────────────

    public JSONObject getResultJson() {
        if (pipelineResult == null) return null;
        return reportAssembler.toResultJson(
                pipelineResult.signedContract(), pipelineResult.originalContract(), pipelineResult.analysisJson());
    }

    public JSONObject getSignedContractJson() {
        if (pipelineResult == null) return null;
        return reportAssembler.toSignedContractJson(pipelineResult.signedContract());
    }

    public JSONObject getOriginalContractJson() {
        if (pipelineResult == null) return null;
        return reportAssembler.toOriginalContractJson(pipelineResult.originalContract());
    }

    public JSONObject getAnalysisJson() {
        if (pipelineResult == null) return null;
        return pipelineResult.analysisJson();
    }

    public java.util.List<AnalysisWarning> getWarnings() {
        return warnings.getWarnings();
    }

    // ── Report export ────────────────────────────────────────────────────────

    public ContractAnalyzer exportHtml(String outputFilePath) throws ContractAnalysisException {
        requireCompleted();
        JSONObject result = getResultJson();
        try {
            String html = postForString("/report/html", result.toString());
            Files.writeString(Path.of(outputFilePath), html, StandardCharsets.UTF_8);
        } catch (ContractAnalysisException connEx) {
            // Fallback: Req 30.1 — only on connection failure, never on 401
            if (isAuthError(connEx)) throw connEx;
            String fallbackHtml = FallbackReportGenerator.generateHtml(result);
            try {
                Files.writeString(Path.of(outputFilePath), fallbackHtml, StandardCharsets.UTF_8);
            } catch (IOException ioEx) {
                throw new ContractAnalysisException("Fallback HTML dosyaya yazılamadı: " + ioEx.getMessage(), ioEx);
            }
            warnings.addWarning("ContractAnalyzer", Severity.WARN,
                    "ReportMicroservice erişilemedi, yerel fallback rapor kullanıldı");
        } catch (IOException e) {
            throw new ContractAnalysisException("HTML dosyaya yazılamadı: " + e.getMessage(), e);
        }
        return this;
    }

    public ContractAnalyzer exportPdf(String outputFilePath) throws ContractAnalysisException {
        requireCompleted();
        JSONObject result = getResultJson();
        try {
            byte[] pdf = postForBytes("/report/pdf", result.toString());
            Files.write(Path.of(outputFilePath), pdf);
        } catch (ContractAnalysisException connEx) {
            if (isAuthError(connEx)) throw connEx;
            String fallbackHtml = FallbackReportGenerator.generateHtml(result);
            try {
                FallbackReportGenerator.convertHtmlToPdf(fallbackHtml, outputFilePath);
            } catch (Exception pdfEx) {
                throw new ContractAnalysisException(
                        "Hem ReportMicroservice hem de yerel PDF dönüştürme başarısız oldu: " + pdfEx.getMessage(), pdfEx);
            }
            warnings.addWarning("ContractAnalyzer", Severity.WARN,
                    "ReportMicroservice erişilemedi, yerel fallback PDF kullanıldı");
        } catch (IOException e) {
            throw new ContractAnalysisException("PDF dosyaya yazılamadı: " + e.getMessage(), e);
        }
        return this;
    }

    private void requireCompleted() {
        if (state != State.COMPLETED) {
            throw new IllegalStateException("start() must complete successfully before export*()/get*Json()");
        }
    }

    private boolean isAuthError(ContractAnalysisException e) {
        return e.getMessage() != null && e.getMessage().contains("401");
    }

    // ── HTTP helpers for /report/* ──────────────────────────────────────────

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private String postForString(String path, String body) throws ContractAnalysisException {
        HttpResponse<String> resp = sendReportRequest(path, body, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private byte[] postForBytes(String path, String body) throws ContractAnalysisException {
        HttpResponse<byte[]> resp = sendReportRequest(path, body, HttpResponse.BodyHandlers.ofByteArray());
        return resp.body();
    }

    private <T> HttpResponse<T> sendReportRequest(String path, String body, HttpResponse.BodyHandler<T> handler)
            throws ContractAnalysisException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(pipelineConfig.pythonServiceUrl() + path))
                    .header("Authorization", "Bearer " + pipelineConfig.pythonServiceApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120)).build();
            HttpResponse<T> resp = http.send(req, handler);
            if (resp.statusCode() == 401) {
                throw new ContractAnalysisException("HTTP 401 Unauthorized: invalid ReportServiceApiKey");
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new ContractAnalysisException("HTTP " + resp.statusCode());
            }
            return resp;
        } catch (ContractAnalysisException e) {
            throw e;
        } catch (Exception e) {
            throw new ContractAnalysisException("ReportMicroservice'e bağlanılamadı: " + e.getMessage(), e);
        }
    }

    // ── Config mapping ───────────────────────────────────────────────────────

    private PipelineConfig toPipelineConfig(JSONObject cfg) {
        return new PipelineConfig(
                cfg.getString("OCR"),
                cfg.optJSONObject("OCR_CONFIG"),
                cfg.getString("AI"),
                cfg.optJSONObject("AI_CONFIG"),
                cfg.optInt("BBOX_MARGIN", 5),
                cfg.getString("PYTHON_SERVICE_URL"),
                cfg.optString("PYTHON_SERVICE_API_KEY", ""),
                cfg.optInt("AI_MAX_CONCURRENCY", 5),
                cfg.optInt("OCR_MAX_CONCURRENCY", 3),
                cfg.optLong("RETENTION_SECONDS", 0),
                cfg.optLong("MAX_FILE_SIZE_MB", 100),
                cfg.optInt("MAX_RESULT_SIZE_MB", 50),
                cfg.optString("PRIMARY_LANGUAGE", null),
                cfg.optString("LOG_FILE_PATH", null),
                cfg.optString("LOG_FORMAT", "plain")
        );
    }

    private long estimateResultSizeMb(PipelineResult result) {
        JSONObject json = reportAssembler.toResultJson(
                result.signedContract(), result.originalContract(), result.analysisJson());
        return json.toString().getBytes(StandardCharsets.UTF_8).length / (1024L * 1024L);
    }
}
