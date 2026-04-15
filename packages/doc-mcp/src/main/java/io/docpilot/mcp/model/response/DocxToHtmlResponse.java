package io.docpilot.mcp.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.docpilot.mcp.model.legacy.StyleRegistry;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocxToHtmlResponse {

    String docId;
    String html;
    StyleRegistry styleRegistry;
    String filename;
    Integer wordCount;
    Integer pageCount;
}
