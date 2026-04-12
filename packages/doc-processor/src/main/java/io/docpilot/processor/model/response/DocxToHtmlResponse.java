package io.docpilot.processor.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.docpilot.processor.model.StyleRegistry;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Response for {@code POST /api/convert/docx-to-html}.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocxToHtmlResponse {

    /** Assigned document identifier (UUID). Pass this to html-to-docx to restore styles. */
    String docId;

    /** Converted HTML string. Images are embedded as base64 data URIs when configured. */
    String html;

    /** Extracted style registry — persist this alongside the HTML for round-trip fidelity. */
    StyleRegistry styleRegistry;

    // Metadata
    String filename;
    Integer wordCount;
    Integer pageCount;
}
