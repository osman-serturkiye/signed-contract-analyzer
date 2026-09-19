package com.signedcontract.report;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Local fallback report generator used only when the Python ReportMicroservice
 * is unreachable (connection failure / 5xx) — never on 401 (Req 30.4).
 *
 * <p>Validates: Requirements 30.1–30.6</p>
 */
public final class FallbackReportGenerator {

    private FallbackReportGenerator() {}

    private static final Parser MARKDOWN_PARSER = Parser.builder(new MutableDataSet()).build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder(new MutableDataSet()).build();

    public static String generateHtml(JSONObject resultJson) {
        JSONObject signed = resultJson.optJSONObject("signed_contract", new JSONObject());
        JSONObject analysis = resultJson.optJSONObject("analysis", new JSONObject());
        JSONObject analyzeClauses = analysis.optJSONObject("analyze_clauses", new JSONObject());

        String title = signed.optString("title", "Sözleşme Analizi");

        StringBuilder rows = new StringBuilder();
        for (String key : analyzeClauses.keySet()) {
            JSONObject entry = analyzeClauses.getJSONObject(key);
            boolean javaChanged = entry.optJSONObject("diff", new JSONObject()).optBoolean("changes", false);
            boolean aiKnown = !entry.optJSONObject("diff_ai", new JSONObject()).isNull("changes");
            boolean aiChanged = aiKnown && entry.optJSONObject("diff_ai", new JSONObject()).optBoolean("changes", false);

            String javaColor = javaChanged ? "#fdd" : "#dfd";
            String aiColor = !aiKnown ? "#eee" : (aiChanged ? "#fdd" : "#dfd");

            String signedImg = entry.isNull("signed_image") || entry.optString("signed_image", "").isEmpty()
                    ? "" : "<img style=\"max-width:180px\" src=\"data:image/jpeg;base64," + entry.optString("signed_image") + "\"/>";

            rows.append("<tr>")
                .append("<td>").append(escape(key)).append("</td>")
                .append("<td>").append(signedImg).append("</td>")
                .append("<td>").append(toHtml(entry.optString("signed_content", ""))).append("</td>")
                .append("<td>").append(toHtml(entry.optString("original_content", ""))).append("</td>")
                .append("<td style=\"background:").append(javaColor).append("\">")
                    .append(toHtml(entry.optJSONObject("diff", new JSONObject()).optString("result", ""))).append("</td>")
                .append("<td style=\"background:").append(aiColor).append("\">")
                    .append(toHtml(entry.optJSONObject("diff_ai", new JSONObject()).optString("result", ""))).append("</td>")
                .append("</tr>\n");
        }

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/>"
                + "<title>" + escape(title) + "</title>"
                + "<style>table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:6px;vertical-align:top}"
                + "th{background:#f0f0f0}</style></head><body>"
                + "<h1>" + escape(title) + "</h1>"
                + "<p>Oluşturulma zamanı: " + java.time.LocalDateTime.now() + "</p>"
                + "<table><thead><tr><th>Madde No</th><th>Clause Image</th><th>Signed Content</th>"
                + "<th>Original Content</th><th>Java Diff</th><th>AI Diff</th></tr></thead>"
                + "<tbody>" + rows + "</tbody></table></body></html>";
    }

    public static void convertHtmlToPdf(String html, String outputFilePath) throws IOException {
        try (FileOutputStream os = new FileOutputStream(outputFilePath)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
        }
    }

    private static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        Node document = MARKDOWN_PARSER.parse(markdown);
        return MARKDOWN_RENDERER.render(document);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
