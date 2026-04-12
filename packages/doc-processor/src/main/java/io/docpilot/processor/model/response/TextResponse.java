package io.docpilot.processor.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Response for {@code POST /api/convert/docx-to-markdown} and {@code /pdf-to-text}.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextResponse {

    String docId;
    String filename;
    String content;   // markdown or plain text
    Integer wordCount;
    Integer charCount;
}
