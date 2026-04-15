package io.docpilot.mcp.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/** Tracks which revision last touched this component. */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RevisionMetadata {
    String createdByRevisionId;
    String lastModifiedByRevisionId;
    Instant lastModifiedAt;
    /** "ai" or "manual" */
    String lastModifiedBy;
}
