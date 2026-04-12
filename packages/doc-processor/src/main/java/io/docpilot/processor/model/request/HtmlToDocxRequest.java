package io.docpilot.processor.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.docpilot.processor.model.StyleRegistry;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code POST /api/convert/html-to-docx}.
 */
@Data
public class HtmlToDocxRequest {

    /**
     * Valid XHTML or HTML5 string to convert.
     * Must be well-formed enough for the parser; ideally the output of a prior
     * {@code docx-to-html} call.
     */
    @NotBlank(message = "html must not be blank")
    private String html;

    /**
     * Optional: the doc ID whose stored style registry should be applied.
     * When supplied, the converter loads the previously extracted StyleRegistry
     * and applies it to the output DOCX — preserving heading fonts, colours, etc.
     */
    @JsonProperty("doc_id")
    private String docId;

    /**
     * Optional: an inline StyleRegistry to use instead of fetching from the store.
     * Takes precedence over docId if both are supplied.
     */
    @JsonProperty("style_registry")
    private StyleRegistry styleRegistry;

    /**
     * Base URL used to resolve relative image / resource URLs in the HTML.
     * Defaults to {@code null} (relative URLs are skipped).
     */
    @JsonProperty("base_url")
    private String baseUrl;
}
