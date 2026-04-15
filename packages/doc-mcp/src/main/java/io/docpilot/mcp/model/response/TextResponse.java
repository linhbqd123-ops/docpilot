package io.docpilot.mcp.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextResponse {

    String docId;
    String filename;
    String content;
    Integer wordCount;
    Integer charCount;
}
