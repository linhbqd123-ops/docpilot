package io.docpilot.processor.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Generic API envelope for error responses.
 * Success responses use typed bodies directly (no envelope wrapper needed).
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse {

    int status;
    String error;
    String message;
    String path;
    String timestamp;
}
