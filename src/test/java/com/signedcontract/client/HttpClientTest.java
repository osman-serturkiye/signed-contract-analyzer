package com.signedcontract.client;

import com.signedcontract.model.*;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.http.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HTTP clients (OcrClient, ImageClient, AiClient, ReportClient).
 *
 * Tests cover:
 *  - 401 propagation: immediate ContractAnalysisException, no retry
 *  - Retry logic: 429/5xx retry up to 3 times (1s, 2s, 4s backoff)
 *  - Timeout/network error behavior
 *
 * Requirements: 23.3, 23.4
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class HttpClientTest {

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> HttpResponse<T> mockResponse(int status, T body) {
        HttpResponse<T> r = (HttpResponse<T>) mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    private static HttpResponse<String> stringResp(int status, String body) {
        return mockResponse(status, body);
    }

    private static HttpResponse<byte[]> bytesResp(int status, byte[] body) {
        return mockResponse(status, body);
    }

    /** Minimal valid OcrResponse JSON. */
    private static String ocrJson() {
        return new JSONObject()
                .put("blocks", new org.json.JSONArray())
                .put("page_width", 800)
                .put("page_height", 1100)
                .toString();
    }

    private static String cropJson() {
        return new JSONObject().put("image", "abc123").put("format", "jpeg").toString();
    }

    private static String stitchJson() {
        return new JSONObject().put("image", "stitched456").put("format", "jpeg").toString();
    }

    private static String aiJson() {
        return new JSONObject().put("changes", false).put("result", "no changes").toString();
    }

    private static PageImage dummyPageImage() {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        return new PageImage(1, img, 100, 100);
    }

    private static BoundingBox bbox() {
        return new BoundingBox(10, 10, 50, 50);
    }

    private static LanguageAnalysis langAnalysis() {
        return new LanguageAnalysis(false, "tr", List.of());
    }

    // =========================================================================
    // OcrClient
    // =========================================================================

    @Nested
    @DisplayName("OcrClient")
    class OcrClientTests {

        HttpClient mockHttp;
        OcrClient client;

        @BeforeEach
        void setUp() throws Exception {
            mockHttp = mock(HttpClient.class);
            client = new OcrClient("http://localhost:8765", "test-key", "paddleocr", mockHttp);
        }

        // -- 401 propagation --------------------------------------------------

        @Test
        @DisplayName("401 -> immediate ContractAnalysisException, no retry")
        void ocr_401_immediate_exception() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(401, "Unauthorized"));

            assertThatThrownBy(() -> client.performOcr(dummyPageImage(), "tr"))
                    .isInstanceOf(ContractAnalysisException.class)
                    .hasMessageContaining("401");

            verify(mockHttp, times(1)).send(any(), any());
        }

        // -- Retry on 429 -----------------------------------------------------

        @Test
        @DisplayName("429 twice then 200 -> succeeds after 2 retries")
        void ocr_429_retry_then_success() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(429, "rate limited"))
                    .thenReturn(stringResp(429, "rate limited"))
                    .thenReturn(stringResp(200, ocrJson()));

            PageOcrResult result = client.performOcr(dummyPageImage(), "tr");

            assertThat(result).isNotNull();
            assertThat(result.pageNumber()).isEqualTo(1);
            verify(mockHttp, times(3)).send(any(), any());
        }

        // -- Retry on 5xx -----------------------------------------------------

        @Test
        @DisplayName("500 four times -> ContractAnalysisException after max retries")
        void ocr_500_exhausts_retries() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(500, "server error"))
                    .thenReturn(stringResp(500, "server error"))
                    .thenReturn(stringResp(500, "server error"))
                    .thenReturn(stringResp(500, "server error"));

            assertThatThrownBy(() -> client.performOcr(dummyPageImage(), "tr"))
                    .isInstanceOf(ContractAnalysisException.class);

            verify(mockHttp, times(4)).send(any(), any());
        }

        @Test
        @DisplayName("503 once then 200 -> succeeds after 1 retry")
        void ocr_503_retry_then_success() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(503, "unavailable"))
                    .thenReturn(stringResp(200, ocrJson()));

            PageOcrResult result = client.performOcr(dummyPageImage(), "tr");

            assertThat(result).isNotNull();
            verify(mockHttp, times(2)).send(any(), any());
        }

        // -- Network/timeout errors -------------------------------------------

        @Test
        @DisplayName("IOException four times -> ContractAnalysisException")
        void ocr_network_error_exhausts_retries() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("connect timed out"))
                    .thenThrow(new IOException("connect timed out"))
                    .thenThrow(new IOException("connect timed out"))
                    .thenThrow(new IOException("connect timed out"));

            assertThatThrownBy(() -> client.performOcr(dummyPageImage(), "tr"))
                    .isInstanceOf(ContractAnalysisException.class)
                    .hasMessageContaining("retries");

            verify(mockHttp, times(4)).send(any(), any());
        }

        @Test
        @DisplayName("IOException once then 200 -> succeeds after 1 retry")
        void ocr_network_error_then_success() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("connection reset"))
                    .thenReturn(stringResp(200, ocrJson()));

            PageOcrResult result = client.performOcr(dummyPageImage(), "tr");

            assertThat(result).isNotNull();
            verify(mockHttp, times(2)).send(any(), any());
        }

        // -- Success ----------------------------------------------------------

        @Test
        @DisplayName("200 -> parses OcrResult correctly")
        void ocr_success_parses_result() throws Exception {
            String json = new JSONObject()
                    .put("page_width", 1654)
                    .put("page_height", 2339)
                    .put("blocks", new org.json.JSONArray()
                            .put(new JSONObject()
                                    .put("type", "text")
                                    .put("bbox", new JSONObject()
                                            .put("x", 10).put("y", 20)
                                            .put("width", 200).put("height", 30))
                                    .put("content", "Madde 1")
                                    .put("confidence", 0.98)))
                    .toString();

            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, json));

            PageOcrResult result = client.performOcr(dummyPageImage(), "tr");

            assertThat(result.pageWidthPx()).isEqualTo(1654);
            assertThat(result.pageHeightPx()).isEqualTo(2339);
            assertThat(result.blocks()).hasSize(1);
            assertThat(result.blocks().get(0).type()).isEqualTo("text");
            assertThat(result.blocks().get(0).content()).isEqualTo("Madde 1");
            assertThat(result.blocks().get(0).confidence()).isEqualTo(0.98);
        }

        @Test
        @DisplayName("null confidence in OCR block -> parsed as null")
        void ocr_null_confidence_block() throws Exception {
            String json = new JSONObject()
                    .put("page_width", 800)
                    .put("page_height", 1100)
                    .put("blocks", new org.json.JSONArray()
                            .put(new JSONObject()
                                    .put("type", "text")
                                    .put("bbox", new JSONObject()
                                            .put("x", 0).put("y", 0)
                                            .put("width", 100).put("height", 20))
                                    .put("content", "test")
                                    .put("confidence", JSONObject.NULL)))
                    .toString();

            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, json));

            PageOcrResult result = client.performOcr(dummyPageImage(), "tr");

            assertThat(result.blocks().get(0).confidence()).isNull();
        }

        @Test
        @DisplayName("Authorization header contains correct API key")
        void ocr_sends_authorization_header() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, ocrJson()));

            client.performOcr(dummyPageImage(), "tr");

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockHttp).send(captor.capture(), any());
            assertThat(captor.getValue().headers().firstValue("Authorization"))
                    .hasValue("Bearer test-key");
        }
    }

    // =========================================================================
    // ImageClient
    // =========================================================================

    @Nested
    @DisplayName("ImageClient")
    class ImageClientTests {

        HttpClient mockHttp;
        ImageClient client;

        @BeforeEach
        void setUp() {
            mockHttp = mock(HttpClient.class);
            client = new ImageClient("http://localhost:8765", "test-key", mockHttp);
        }

        // -- 401 propagation --------------------------------------------------

        @Test
        @DisplayName("crop 401 -> immediate ContractAnalysisException, no retry")
        void imageCrop_401_immediate() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(401, "Unauthorized"));

            assertThatThrownBy(() -> client.crop("base64img", bbox(), 5, null, null))
                    .isInstanceOf(ContractAnalysisException.class)
                    .hasMessageContaining("401");

            verify(mockHttp, times(1)).send(any(), any());
        }

        @Test
        @DisplayName("stitch 401 -> immediate ContractAnalysisException, no retry")
        void imageStitch_401_immediate() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(401, "Unauthorized"));

            assertThatThrownBy(() -> client.stitch(List.of("img1", "img2"), 2))
                    .isInstanceOf(ContractAnalysisException.class)
                    .hasMessageContaining("401");

            verify(mockHttp, times(1)).send(any(), any());
        }

        // -- Retry on 429/5xx -------------------------------------------------

        @Test
        @DisplayName("crop 429 twice then 200 -> succeeds after 2 retries")
        void imageCrop_429_retry_then_success() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(429, "rate limited"))
                    .thenReturn(stringResp(429, "rate limited"))
                    .thenReturn(stringResp(200, cropJson()));

            String result = client.crop("base64img", bbox(), 5, null, null);

            assertThat(result).isEqualTo("abc123");
            verify(mockHttp, times(3)).send(any(), any());
        }

        @Test
        @DisplayName("stitch 500 four times -> ContractAnalysisException after max retries")
        void imageStitch_500_exhausts_retries() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(500, "error"))
                    .thenReturn(stringResp(500, "error"))
                    .thenReturn(stringResp(500, "error"))
                    .thenReturn(stringResp(500, "error"));

            assertThatThrownBy(() -> client.stitch(List.of("img1"), 2))
                    .isInstanceOf(ContractAnalysisException.class);

            verify(mockHttp, times(4)).send(any(), any());
        }

        // -- Network errors ---------------------------------------------------

        @Test
        @DisplayName("crop IOException four times -> ContractAnalysisException")
        void imageCrop_networkError_exhausts_retries() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("timeout"))
                    .thenThrow(new IOException("timeout"))
                    .thenThrow(new IOException("timeout"))
                    .thenThrow(new IOException("timeout"));

            assertThatThrownBy(() -> client.crop("img", bbox(), 5, null, null))
                    .isInstanceOf(ContractAnalysisException.class);

            verify(mockHttp, times(4)).send(any(), any());
        }

        // -- Success ----------------------------------------------------------

        @Test
        @DisplayName("crop 200 -> returns base64 image string")
        void imageCrop_success() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, cropJson()));

            String result = client.crop("base64img", bbox(), 5, 2000, 0.85);

            assertThat(result).isEqualTo("abc123");
        }

        @Test
        @DisplayName("stitch 200 -> returns stitched base64 image string")
        void imageStitch_success() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, stitchJson()));

            String result = client.stitch(List.of("img1", "img2"), 2);

            assertThat(result).isEqualTo("stitched456");
        }

        @Test
        @DisplayName("crop sends Authorization header with API key")
        void imageCrop_sends_authorization_header() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, cropJson()));

            client.crop("base64img", bbox(), 5, null, null);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockHttp).send(captor.capture(), any());
            assertThat(captor.getValue().headers().firstValue("Authorization"))
                    .hasValue("Bearer test-key");
        }
    }

    // =========================================================================
    // AiClient
    // =========================================================================

    @Nested
    @DisplayName("AiClient")
    class AiClientTests {

        HttpClient mockHttp;
        AiClient client;

        @BeforeEach
        void setUp() {
            mockHttp = mock(HttpClient.class);
            client = new AiClient("http://localhost:8765", "test-key", mockHttp);
        }

        // -- 401 propagation --------------------------------------------------

        @Test
        @DisplayName("401 -> immediate ContractAnalysisException, no retry")
        void ai_401_immediate() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(401, "Unauthorized"));

            assertThatThrownBy(() -> client.compare("signed", "original", langAnalysis()))
                    .isInstanceOf(ContractAnalysisException.class)
                    .hasMessageContaining("401");

            verify(mockHttp, times(1)).send(any(), any());
        }

        // -- Retry on 429/5xx -------------------------------------------------

        @Test
        @DisplayName("429 four times -> ContractAnalysisException after max retries")
        void ai_429_exhausts_retries() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(429, "rate limited"))
                    .thenReturn(stringResp(429, "rate limited"))
                    .thenReturn(stringResp(429, "rate limited"))
                    .thenReturn(stringResp(429, "rate limited"));

            assertThatThrownBy(() -> client.compare("signed", "original", langAnalysis()))
                    .isInstanceOf(ContractAnalysisException.class);

            verify(mockHttp, times(4)).send(any(), any());
        }

        @Test
        @DisplayName("500 once then 200 -> succeeds after 1 retry")
        void ai_500_retry_then_success() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(500, "error"))
                    .thenReturn(stringResp(200, aiJson()));

            AiCompareResult result = client.compare("signed", "original", langAnalysis());

            assertThat(result).isNotNull();
            assertThat(result.changes()).isFalse();
            verify(mockHttp, times(2)).send(any(), any());
        }

        // -- Network errors ---------------------------------------------------

        @Test
        @DisplayName("IOException four times -> ContractAnalysisException")
        void ai_networkError_exhausts_retries() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("timeout"))
                    .thenThrow(new IOException("timeout"))
                    .thenThrow(new IOException("timeout"))
                    .thenThrow(new IOException("timeout"));

            assertThatThrownBy(() -> client.compare("s", "o", null))
                    .isInstanceOf(ContractAnalysisException.class);

            verify(mockHttp, times(4)).send(any(), any());
        }

        // -- Success ----------------------------------------------------------

        @Test
        @DisplayName("200 with changes=true -> parsed correctly")
        void ai_success_changes_true() throws Exception {
            String json = new JSONObject().put("changes", true).put("result", "Ucret degisti").toString();
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, json));

            AiCompareResult result = client.compare("signed", "original", langAnalysis());

            assertThat(result.changes()).isTrue();
            assertThat(result.result()).isEqualTo("Ucret degisti");
        }

        @Test
        @DisplayName("200 with changes=null -> parsed as null (service access failure)")
        void ai_success_changes_null() throws Exception {
            String json = new JSONObject()
                    .put("changes", JSONObject.NULL)
                    .put("result", "Servis erisim hatasi")
                    .toString();
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, json));

            AiCompareResult result = client.compare("signed", "original", null);

            assertThat(result.changes()).isNull();
            assertThat(result.result()).isEqualTo("Servis erisim hatasi");
        }

        @Test
        @DisplayName("sends Authorization header with API key")
        void ai_sends_authorization_header() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, aiJson()));

            client.compare("signed", "original", langAnalysis());

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockHttp).send(captor.capture(), any());
            assertThat(captor.getValue().headers().firstValue("Authorization"))
                    .hasValue("Bearer test-key");
        }
    }

    // =========================================================================
    // ReportClient
    // =========================================================================

    @Nested
    @DisplayName("ReportClient")
    class ReportClientTests {

        HttpClient mockHttp;
        ReportClient client;
        JSONObject dummyPayload;

        @BeforeEach
        void setUp() {
            mockHttp = mock(HttpClient.class);
            client = new ReportClient("http://localhost:8765", "test-key", mockHttp);
            dummyPayload = new JSONObject().put("signed_contract", new JSONObject());
        }

        // -- 401 propagation --------------------------------------------------

        @Test
        @DisplayName("generateHtml 401 -> immediate ContractAnalysisException, no retry")
        void reportHtml_401_immediate() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(401, "Unauthorized"));

            assertThatThrownBy(() -> client.generateHtml(dummyPayload))
                    .isInstanceOf(ContractAnalysisException.class)
                    .hasMessageContaining("401");

            verify(mockHttp, times(1)).send(any(), any());
        }

        @Test
        @DisplayName("generatePdf 401 -> immediate ContractAnalysisException, no retry")
        void reportPdf_401_immediate() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(bytesResp(401, new byte[0]));

            assertThatThrownBy(() -> client.generatePdf(dummyPayload))
                    .isInstanceOf(ContractAnalysisException.class)
                    .hasMessageContaining("401");

            verify(mockHttp, times(1)).send(any(), any());
        }

        // -- Retry on 429/5xx -------------------------------------------------

        @Test
        @DisplayName("generateHtml 429 twice then 200 -> succeeds after 2 retries")
        void reportHtml_429_retry_then_success() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(429, "rate limited"))
                    .thenReturn(stringResp(429, "rate limited"))
                    .thenReturn(stringResp(200, "<html>report</html>"));

            String result = client.generateHtml(dummyPayload);

            assertThat(result).isEqualTo("<html>report</html>");
            verify(mockHttp, times(3)).send(any(), any());
        }

        @Test
        @DisplayName("generateHtml 500 four times -> ContractAnalysisException after max retries")
        void reportHtml_500_exhausts_retries() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(500, "error"))
                    .thenReturn(stringResp(500, "error"))
                    .thenReturn(stringResp(500, "error"))
                    .thenReturn(stringResp(500, "error"));

            assertThatThrownBy(() -> client.generateHtml(dummyPayload))
                    .isInstanceOf(ContractAnalysisException.class);

            verify(mockHttp, times(4)).send(any(), any());
        }

        @Test
        @DisplayName("generatePdf 503 once then 200 -> succeeds after 1 retry")
        void reportPdf_503_retry_then_success() throws Exception {
            byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46}; // %PDF
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(bytesResp(503, new byte[0]))
                    .thenReturn(bytesResp(200, pdfBytes));

            byte[] result = client.generatePdf(dummyPayload);

            assertThat(result).isEqualTo(pdfBytes);
            verify(mockHttp, times(2)).send(any(), any());
        }

        // -- Network errors ---------------------------------------------------

        @Test
        @DisplayName("generateHtml IOException four times -> ContractAnalysisException")
        void reportHtml_networkError_exhausts_retries() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("timeout"))
                    .thenThrow(new IOException("timeout"))
                    .thenThrow(new IOException("timeout"))
                    .thenThrow(new IOException("timeout"));

            assertThatThrownBy(() -> client.generateHtml(dummyPayload))
                    .isInstanceOf(ContractAnalysisException.class);

            verify(mockHttp, times(4)).send(any(), any());
        }

        // -- Success ----------------------------------------------------------

        @Test
        @DisplayName("generateHtml 200 -> returns HTML string")
        void reportHtml_success() throws Exception {
            String html = "<html><body>report</body></html>";
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, html));

            assertThat(client.generateHtml(dummyPayload)).isEqualTo(html);
        }

        @Test
        @DisplayName("generatePdf 200 -> returns PDF bytes")
        void reportPdf_success() throws Exception {
            byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46};
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(bytesResp(200, pdfBytes));

            assertThat(client.generatePdf(dummyPayload)).isEqualTo(pdfBytes);
        }

        @Test
        @DisplayName("generateHtml sends Authorization header with API key")
        void reportHtml_sends_authorization_header() throws Exception {
            when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(stringResp(200, "<html/>"));

            client.generateHtml(dummyPayload);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockHttp).send(captor.capture(), any());
            assertThat(captor.getValue().headers().firstValue("Authorization"))
                    .hasValue("Bearer test-key");
        }
    }
}
