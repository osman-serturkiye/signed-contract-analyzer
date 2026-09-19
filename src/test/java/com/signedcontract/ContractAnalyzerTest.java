package com.signedcontract;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the parts of {@link ContractAnalyzer}'s contract that don't require
 * a live/mock Python microservice: fluent chaining, pre-start() null returns,
 * and the required call order.
 */
class ContractAnalyzerTest {

    private JSONObject validConfig() {
        return new JSONObject()
                .put("OCR", "PaddleOCR")
                .put("AI", "OpenAI")
                .put("PYTHON_SERVICE_URL", "http://localhost:8765")
                .put("AI_CONFIG", new JSONObject().put("apiKey", "test-key"));
    }

    @Test
    void config_returnsThis_forChaining() {
        ContractAnalyzer analyzer = new ContractAnalyzer();
        assertSame(analyzer, analyzer.config(validConfig()));
    }

    @Test
    void getResultJson_beforeStart_returnsNull() {
        ContractAnalyzer analyzer = new ContractAnalyzer();
        assertNull(analyzer.getResultJson());
        assertNull(analyzer.getSignedContractJson());
        assertNull(analyzer.getOriginalContractJson());
        assertNull(analyzer.getAnalysisJson());
    }

    @Test
    void getWarnings_beforeStart_returnsEmptyList() {
        ContractAnalyzer analyzer = new ContractAnalyzer();
        assertTrue(analyzer.getWarnings().isEmpty());
    }

    @Test
    void start_withoutConfig_throwsIllegalStateException() {
        ContractAnalyzer analyzer = new ContractAnalyzer();
        assertThrows(IllegalStateException.class, analyzer::start);
    }

    @Test
    void start_withoutSignedPdf_throwsIllegalStateException() {
        ContractAnalyzer analyzer = new ContractAnalyzer().config(validConfig());
        assertThrows(IllegalStateException.class, analyzer::start);
    }

    @Test
    void setSignedContractPdf_missingFile_throwsIllegalArgumentException() {
        ContractAnalyzer analyzer = new ContractAnalyzer();
        assertThrows(IllegalArgumentException.class,
                () -> analyzer.setSignedContractPdf("/nonexistent/path/signed.pdf"));
    }

    @Test
    void exportHtml_beforeStart_throwsIllegalStateException() {
        ContractAnalyzer analyzer = new ContractAnalyzer();
        assertThrows(IllegalStateException.class, () -> analyzer.exportHtml("out.html"));
    }

    @Test
    void exportPdf_beforeStart_throwsIllegalStateException() {
        ContractAnalyzer analyzer = new ContractAnalyzer();
        assertThrows(IllegalStateException.class, () -> analyzer.exportPdf("out.pdf"));
    }

    @Test
    void config_withInvalidAdapter_throwsIllegalArgumentException() {
        ContractAnalyzer analyzer = new ContractAnalyzer();
        JSONObject bad = validConfig().put("OCR", "NotARealAdapter");
        assertThrows(IllegalArgumentException.class, () -> analyzer.config(bad));
    }
}
