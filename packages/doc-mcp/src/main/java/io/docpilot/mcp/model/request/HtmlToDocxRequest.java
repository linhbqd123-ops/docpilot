package io.docpilot.mcp.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.docpilot.mcp.model.legacy.StyleRegistry;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code POST /api/convert/html-to-docx}.
 */
@Data
public class HtmlToDocxRequest {

    @NotBlank(message = "html must not be blank")
    private String html;

    @JsonProperty("doc_id")
    private String docId;

    @JsonProperty("style_registry")
    private StyleRegistry styleRegistry;

    @JsonProperty("base_url")
    private String baseUrl;
}
