package io.docpilot.mcp.model.revision;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * A revision that could not be automatically merged because the agent's working set
 * overlaps with a concurrent manual edit.
 *
 * <p>The conflict must be resolved by the user before the agent patch can be applied.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConflictRevision {

    String conflictId;
    String sessionId;

    /** The agent patch that triggered the conflict. */
    String agentPatchId;
    /** The manual revision that overlaps with the agent working set. */
    String manualRevisionId;

    /** Block stableIds where both patches overlap. */
    List<String> conflictingBlockIds;

    /** Summary of the agent's intended change. */
    String agentChangeSummary;
    /** Summary of what the manual edit did. */
    String manualChangeSummary;

    /**
     * Resolution options surfaced to the user:
     * "KEEP_AGENT" | "KEEP_MANUAL" | "MANUAL_MERGE"
     */
    String resolution;
}
