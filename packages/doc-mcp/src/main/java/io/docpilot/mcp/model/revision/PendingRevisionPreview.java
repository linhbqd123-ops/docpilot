package io.docpilot.mcp.model.revision;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.docpilot.mcp.model.diff.DocumentDiff;
import io.docpilot.mcp.model.patch.PatchValidation;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PendingRevisionPreview {

    String revisionId;
    String sessionId;
    String baseRevisionId;
    String currentRevisionId;

    boolean available;
    String message;

    String html;
    String sourceHtml;

    PatchValidation validation;
    DocumentDiff diff;
}