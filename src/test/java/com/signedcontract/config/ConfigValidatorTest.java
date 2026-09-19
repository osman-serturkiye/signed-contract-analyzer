package com.signedcontract.config;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfigValidator}.
 *
 * <p>Covers all invalid configuration scenarios (Requirements 25.1–25.5) as well as
 * valid configurations, {@code getRequired}, and {@code getOptional} accessor behaviour.</p>
 */
class ConfigValidatorTest {

    private ConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ConfigValidator();
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    /**
     * Returns a minimal valid configuration that uses PaddleOCR and OpenAI.
     */
    private JSONObject validBase() {
        JSONObject cfg = new JSONObject();
        cfg.put("OCR", "PaddleOCR");
        cfg.put("AI", "OpenAI");
        cfg.put("PYTHON_SERVICE_URL", "http://localhost:8765");
        cfg.put("AI_CONFIG", new JSONObject().put("apiKey", "k"));
        return cfg;
    }

    // ── 1. Negative BBOX_MARGIN ───────────────────────────────────────────────

    @Test
    void validate_negativeBboxMargin_throwsIllegalArgumentException() {
        JSONObject cfg = validBase();
        cfg.put("BBOX_MARGIN", -1);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(cfg));
    }

    // ── 2. Invalid OCR adapter ────────────────────────────────────────────────

    @Test
    void validate_invalidOcrAdapter_throwsIllegalArgumentException() {
        JSONObject cfg = validBase();
        cfg.put("OCR", "UnknownOCR");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(cfg));
    }

    // ── 3. Invalid AI adapter ─────────────────────────────────────────────────

    @Test
    void validate_invalidAiAdapter_throwsIllegalArgumentException() {
        JSONObject cfg = validBase();
        cfg.put("AI", "UnknownAI");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(cfg));
    }

    // ── 4. Missing PYTHON_SERVICE_URL ─────────────────────────────────────────

    @Test
    void validate_missingPythonServiceUrl_throwsIllegalArgumentException() {
        JSONObject cfg = validBase();
        cfg.remove("PYTHON_SERVICE_URL");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(cfg));
    }

    // ── 5. Invalid URL scheme (ftp://) ────────────────────────────────────────

    @Test
    void validate_invalidUrlScheme_throwsIllegalArgumentException() {
        JSONObject cfg = validBase();
        cfg.put("PYTHON_SERVICE_URL", "ftp://host");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(cfg));
    }

    // ── 6. AzureCognitiveService without OCR_CONFIG.endpoint ─────────────────

    @Test
    void validate_azureCognitiveServiceMissingEndpoint_throwsIllegalArgumentException() {
        JSONObject cfg = validBase();
        cfg.put("OCR", "AzureCognitiveService");
        // Provide apiKey but omit endpoint
        cfg.put("OCR_CONFIG", new JSONObject().put("apiKey", "key"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(cfg));
    }

    // ── 7. MistralOcr without OCR_CONFIG.apiKey ──────────────────────────────

    @Test
    void validate_mistralOcrMissingApiKey_throwsIllegalArgumentException() {
        JSONObject cfg = validBase();
        cfg.put("OCR", "MistralOcr");
        // OCR_CONFIG present but missing apiKey
        cfg.put("OCR_CONFIG", new JSONObject());

        assertThrows(IllegalArgumentException.class, () -> validator.validate(cfg));
    }

    // ── 8. OpenAI without AI_CONFIG.apiKey ───────────────────────────────────

    @Test
    void validate_openAiMissingApiKey_throwsIllegalArgumentException() {
        JSONObject cfg = validBase();
        cfg.remove("AI_CONFIG"); // remove the pre-populated AI_CONFIG

        assertThrows(IllegalArgumentException.class, () -> validator.validate(cfg));
    }

    // ── 9. AzureFoundryAI without AI_CONFIG.endpoint ─────────────────────────

    @Test
    void validate_azureFoundryAiMissingEndpoint_throwsIllegalArgumentException() {
        JSONObject cfg = validBase();
        cfg.put("AI", "AzureFoundryAI");
        // apiKey present but endpoint missing
        cfg.put("AI_CONFIG", new JSONObject().put("apiKey", "k"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(cfg));
    }

    // ── 10. Valid config: PaddleOCR + OpenAI ──────────────────────────────────

    @Test
    void validate_validPaddleOcrOpenAi_noException() {
        JSONObject cfg = validBase(); // PaddleOCR + OpenAI with apiKey

        assertDoesNotThrow(() -> validator.validate(cfg));
    }

    // ── 11. Valid config: AzureCognitiveService + ClaudeAI ───────────────────

    @Test
    void validate_validAzureCognitiveServiceClaudeAi_noException() {
        JSONObject cfg = new JSONObject();
        cfg.put("OCR", "AzureCognitiveService");
        cfg.put("AI", "ClaudeAI");
        cfg.put("PYTHON_SERVICE_URL", "https://api.example.com");
        cfg.put("OCR_CONFIG", new JSONObject()
                .put("endpoint", "https://ocr.example.com")
                .put("apiKey", "ocrKey"));
        cfg.put("AI_CONFIG", new JSONObject()
                .put("apiKey", "aiKey"));

        assertDoesNotThrow(() -> validator.validate(cfg));
    }

    // ── 12. getRequired on missing key ────────────────────────────────────────

    @Test
    void getRequired_missingKey_throwsWithExpectedMessage() {
        JSONObject cfg = new JSONObject();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.getRequired(cfg, "MISSING_KEY", String.class));

        assertTrue(ex.getMessage().contains("Missing required config key:"),
                "Exception message should contain 'Missing required config key:'");
    }

    // ── 13. getOptional on missing key returns default value ──────────────────

    @Test
    void getOptional_missingKey_returnsDefaultValue() {
        JSONObject cfg = new JSONObject();

        String result = validator.getOptional(cfg, "MISSING_KEY", String.class, "default");

        assertEquals("default", result);
    }
}
