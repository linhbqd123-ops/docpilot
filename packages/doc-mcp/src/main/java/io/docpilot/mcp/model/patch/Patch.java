package io.docpilot.mcp.model.patch;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.List;

/**
 * A named, ordered set of {@link PatchOperation}s targeting a single document session.
 *
 * <p>A patch always references a {@code baseRevisionId} (the revision the AI / user
 * was looking at when they composed the operations).  The patch engine uses this to
 * detect concurrent edits and decide whether to rebase or raise a conflict.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Patch {

    String patchId;
    String sessionId;

    /**
     * Revision the author was based on when composing these operations.
     * Used by the optimistic-concurrency rebase algorithm.
     */
    String baseRevisionId;

    List<PatchOperation> operations;

    /** Short human-readable summary of what this patch does. */
    String summary;

    /** Validation result filled in after {@code dry_run_patch}. */
    PatchValidation validation;

    Instant createdAt;

    /** "ai" | "manual" — who created this patch. */
    String author;

    /**
     * Working-set block stableIds — blocks the patch intends to mutate.
     * Used by the conflict detector to check overlap with concurrent manual edits.
     */
    List<String> workingSet;
}
