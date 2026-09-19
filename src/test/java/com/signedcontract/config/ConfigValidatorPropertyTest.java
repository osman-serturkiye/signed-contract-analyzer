package com.signedcontract.config;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.json.JSONObject;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for {@link ConfigValidator}.
 *
 * <p><b>Validates: Requirements 25.2, 25.3</b>
 *
 * <p>Property 1 (Req 25.2): Any string that is not a valid OCR adapter name must be
 * rejected with an {@link IllegalArgumentException} that mentions the invalid value.
 *
 * <p>Property 2 (Req 25.3): Any string that is not a valid AI adapter name must be
 * rejected with an {@link IllegalArgumentException} that mentions the invalid value.
 */
class ConfigValidatorPropertyTest {

    private static final Set<String> VALID_OCR = Set.of(
        "PaddleOCR", "Surya", "AzureCognitiveService", "MicrosoftFoundry", "MistralOcr"
    );
    private static final Set<String> VALID_AI = Set.of(
        "OpenAI", "AzureFoundryAI", "ClaudeAI", "OpenRouterAI"
    );

    /**
     * Property: every alphabetic string that is not in the valid OCR adapter set
     * must cause {@code validate()} to throw {@link IllegalArgumentException}
     * whose message contains the invalid adapter name.
     *
     * <b>Validates: Requirements 25.2</b>
     */
    @Property
    void invalidOcrAdapterAlwaysThrows(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String invalidOcr) {
        Assume.that(!VALID_OCR.contains(invalidOcr));
        ConfigValidator validator = new ConfigValidator();
        JSONObject config = new JSONObject();
        config.put("OCR", invalidOcr);
        config.put("AI", "OpenAI");
        config.put("PYTHON_SERVICE_URL", "http://localhost:8765");
        config.put("AI_CONFIG", new JSONObject().put("apiKey", "test-key"));
        assertThatThrownBy(() -> validator.validate(config))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(invalidOcr);
    }

    /**
     * Property: every alphabetic string that is not in the valid AI adapter set
     * must cause {@code validate()} to throw {@link IllegalArgumentException}
     * whose message contains the invalid adapter name.
     *
     * <b>Validates: Requirements 25.3</b>
     */
    @Property
    void invalidAiAdapterAlwaysThrows(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String invalidAi) {
        Assume.that(!VALID_AI.contains(invalidAi));
        ConfigValidator validator = new ConfigValidator();
        JSONObject config = new JSONObject();
        config.put("OCR", "PaddleOCR");
        config.put("AI", invalidAi);
        config.put("PYTHON_SERVICE_URL", "http://localhost:8765");
        assertThatThrownBy(() -> validator.validate(config))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(invalidAi);
    }
}
