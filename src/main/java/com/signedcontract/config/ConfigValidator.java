package com.signedcontract.config;

import org.json.JSONObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;

/**
 * Validates a {@link JSONObject} configuration passed to {@code ContractAnalyzer.config()}.
 *
 * <p>Throws {@link IllegalArgumentException} when any validation rule is violated.
 * All required fields ({@code OCR}, {@code AI}, {@code PYTHON_SERVICE_URL}) must be present.
 */
public class ConfigValidator {

    // ── Valid adapter names ──────────────────────────────────────────────────

    private static final Set<String> VALID_OCR_ADAPTERS = Set.of(
            "PaddleOCR",
            "Surya",
            "AzureCognitiveService",
            "MicrosoftFoundry",
            "MistralOcr"
    );

    private static final Set<String> VALID_AI_ADAPTERS = Set.of(
            "OpenAI",
            "AzureFoundryAI",
            "ClaudeAI",
            "OpenRouterAI"
    );

    // ────────────────────────────────────────────────────────────────────────

    /**
     * Validates the provided configuration object.
     *
     * @param config the JSON configuration to validate
     * @throws IllegalArgumentException if any validation rule is violated or a
     *                                  required field is missing
     */
    public void validate(JSONObject config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration must not be null.");
        }

        // ── Required top-level fields ──────────────────────────────────────
        String ocr = getRequired(config, "OCR", String.class);
        String ai  = getRequired(config, "AI", String.class);
        getRequired(config, "PYTHON_SERVICE_URL", String.class);

        // ── OCR adapter value ──────────────────────────────────────────────
        if (!VALID_OCR_ADAPTERS.contains(ocr)) {
            throw new IllegalArgumentException(
                    "Invalid OCR adapter: \"" + ocr + "\". "
                    + "Must be one of: " + VALID_OCR_ADAPTERS + ".");
        }

        // ── AI adapter value ───────────────────────────────────────────────
        if (!VALID_AI_ADAPTERS.contains(ai)) {
            throw new IllegalArgumentException(
                    "Invalid AI adapter: \"" + ai + "\". "
                    + "Must be one of: " + VALID_AI_ADAPTERS + ".");
        }

        // ── BBOX_MARGIN must not be negative ───────────────────────────────
        int bboxMargin = getOptional(config, "BBOX_MARGIN", Integer.class, 5);
        if (bboxMargin < 0) {
            throw new IllegalArgumentException(
                    "BBOX_MARGIN must not be negative, but was: " + bboxMargin + ".");
        }

        // ── PYTHON_SERVICE_URL must be a valid URL ─────────────────────────
        String pythonServiceUrl = getRequired(config, "PYTHON_SERVICE_URL", String.class);
        validateUrl(pythonServiceUrl);

        // ── OCR_CONFIG sub-fields required by adapter ──────────────────────
        validateOcrConfig(config, ocr);

        // ── AI_CONFIG sub-fields required by adapter ───────────────────────
        validateAiConfig(config, ai);
    }

    // ── Sub-config validators ────────────────────────────────────────────────

    /**
     * Validates that {@code OCR_CONFIG} contains the sub-fields required by the
     * chosen OCR adapter.
     *
     * <p>{@code PaddleOCR} and {@code Surya} go through the Python service and
     * do not require any {@code OCR_CONFIG} sub-fields.</p>
     */
    private void validateOcrConfig(JSONObject config, String ocrAdapter) {
        switch (ocrAdapter) {
            case "AzureCognitiveService":
            case "MicrosoftFoundry":
                requireOcrSubFields(config, ocrAdapter, "endpoint", "apiKey");
                break;
            case "MistralOcr":
                requireOcrSubFields(config, ocrAdapter, "apiKey");
                break;
            // PaddleOCR and Surya: OCR_CONFIG not required
            case "PaddleOCR":
            case "Surya":
            default:
                break;
        }
    }

    /**
     * Validates that {@code AI_CONFIG} contains the sub-fields required by the
     * chosen AI adapter.
     */
    private void validateAiConfig(JSONObject config, String aiAdapter) {
        switch (aiAdapter) {
            case "AzureFoundryAI":
                requireAiSubFields(config, aiAdapter, "endpoint", "apiKey");
                break;
            case "OpenAI":
            case "ClaudeAI":
            case "OpenRouterAI":
                requireAiSubFields(config, aiAdapter, "apiKey");
                break;
            default:
                break;
        }
    }

    /**
     * Ensures {@code OCR_CONFIG} exists and contains the given keys.
     */
    private void requireOcrSubFields(JSONObject config, String adapterName, String... keys) {
        if (!config.has("OCR_CONFIG") || config.isNull("OCR_CONFIG")) {
            throw new IllegalArgumentException(
                    "OCR_CONFIG is required for adapter \"" + adapterName + "\".");
        }
        JSONObject ocrConfig = config.getJSONObject("OCR_CONFIG");
        for (String key : keys) {
            if (!ocrConfig.has(key) || ocrConfig.isNull(key)) {
                throw new IllegalArgumentException(
                        "OCR_CONFIG." + key + " is required for adapter \"" + adapterName + "\".");
            }
            String value = ocrConfig.optString(key, "").trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "OCR_CONFIG." + key + " must not be empty for adapter \"" + adapterName + "\".");
            }
        }
    }

    /**
     * Ensures {@code AI_CONFIG} exists and contains the given keys.
     */
    private void requireAiSubFields(JSONObject config, String adapterName, String... keys) {
        if (!config.has("AI_CONFIG") || config.isNull("AI_CONFIG")) {
            throw new IllegalArgumentException(
                    "AI_CONFIG is required for adapter \"" + adapterName + "\".");
        }
        JSONObject aiConfig = config.getJSONObject("AI_CONFIG");
        for (String key : keys) {
            if (!aiConfig.has(key) || aiConfig.isNull(key)) {
                throw new IllegalArgumentException(
                        "AI_CONFIG." + key + " is required for adapter \"" + adapterName + "\".");
            }
            String value = aiConfig.optString(key, "").trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "AI_CONFIG." + key + " must not be empty for adapter \"" + adapterName + "\".");
            }
        }
    }

    /**
     * Validates that {@code urlString} is a well-formed URL starting with
     * {@code http://} or {@code https://}.
     *
     * @throws IllegalArgumentException if the URL is malformed or uses an unsupported scheme
     */
    private void validateUrl(String urlString) {
        if (urlString == null || (!urlString.startsWith("http://") && !urlString.startsWith("https://"))) {
            throw new IllegalArgumentException(
                    "PYTHON_SERVICE_URL must start with http:// or https://: \"" + urlString + "\".");
        }
        try {
            new URL(urlString);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(
                    "PYTHON_SERVICE_URL is not a valid URL: \"" + urlString + "\".", e);
        }
    }

    // ── Type-safe accessors ──────────────────────────────────────────────────

    /**
     * Reads a required field from {@code config}, casting it to {@code type}.
     *
     * @param config the JSON configuration object
     * @param key    the field name
     * @param type   the expected Java type
     * @param <T>    the return type
     * @return the field value cast to {@code T}
     * @throws IllegalArgumentException if the field is absent, null, or the wrong type
     */
    public <T> T getRequired(JSONObject config, String key, Class<T> type) {
        if (!config.has(key) || config.isNull(key)) {
            throw new IllegalArgumentException("Missing required config key: " + key);
        }
        return castValue(config, key, type);
    }

    /**
     * Reads an optional field from {@code config}, returning {@code defaultValue} when absent.
     *
     * @param config       the JSON configuration object
     * @param key          the field name
     * @param type         the expected Java type
     * @param defaultValue the value to return when the field is absent or null
     * @param <T>          the return type
     * @return the field value, or {@code defaultValue} if absent/null
     * @throws IllegalArgumentException if the field is present but the wrong type
     */
    public <T> T getOptional(JSONObject config, String key, Class<T> type, T defaultValue) {
        if (!config.has(key) || config.isNull(key)) {
            return defaultValue;
        }
        return castValue(config, key, type);
    }

    /**
     * Extracts and casts the value at {@code key} in {@code config} to {@code type}.
     *
     * @throws IllegalArgumentException if the value cannot be cast to {@code type}
     */
    @SuppressWarnings("unchecked")
    private <T> T castValue(JSONObject config, String key, Class<T> type) {
        Object raw = config.get(key);

        // org.json stores integers as Integer but the key may also hold a long
        if (type == Integer.class || type == int.class) {
            if (raw instanceof Number) {
                return type.cast(((Number) raw).intValue());
            }
        }
        if (type == Long.class || type == long.class) {
            if (raw instanceof Number) {
                return type.cast(((Number) raw).longValue());
            }
        }
        if (type == Double.class || type == double.class) {
            if (raw instanceof Number) {
                return type.cast(((Number) raw).doubleValue());
            }
        }
        if (type == Boolean.class || type == boolean.class) {
            if (raw instanceof Boolean) {
                return type.cast(raw);
            }
        }
        if (type == String.class) {
            if (raw instanceof String) {
                return type.cast(raw);
            }
        }
        if (type == JSONObject.class) {
            if (raw instanceof JSONObject) {
                return type.cast(raw);
            }
        }

        if (type.isInstance(raw)) {
            return type.cast(raw);
        }

        throw new IllegalArgumentException(
                "Configuration field \"" + key + "\" has an unexpected type. "
                + "Expected " + type.getSimpleName() + " but got "
                + (raw == null ? "null" : raw.getClass().getSimpleName()) + ".");
    }
}
