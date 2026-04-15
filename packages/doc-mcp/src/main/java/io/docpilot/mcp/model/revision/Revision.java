package io.docpilot.mcp.model.revision;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/**
 * A single entry in the document's revision history.
 *
 * <p>Each revision wraps one applied {@link io.docpilot.mcp.model.patch.Patch}.
 * The chain {@code baseRevisionId → revisionId} forms a linked list that can be
 * traversed to roll back to any previous state.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Revision {

    String revisionId;
    String sessionId;
    String baseRevisionId;

    /** The patch that produced this revision. */
    String patchId;

    RevisionStatus status;

    Instant createdAt;
    Instant appliedAt;

    String summary;

    /** "ai" | "manual" */
    String author;

    /** High-level scope estimate from patch validation. */
    String scope;
}
