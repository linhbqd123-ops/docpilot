package io.docpilot.mcp.model.patch;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Precise target address for a patch operation.
 *
 * <p>Resolution order: {@code blockId} (stableId) → {@code runId} → character range.
 * For table operations, {@code rowId} and {@code cellId} narrow the target further.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PatchTarget {
    /** stableId of the target block/component. */
    String blockId;
    /** stableId of the target run within the block (for run-level ops). */
    String runId;
    /** Character start offset within the run/block text (0-based, inclusive). */
    Integer start;
    /** Character end offset (0-based, exclusive). */
    Integer end;

    // ── Table-specific ──
    String tableId;
    String rowId;
    String cellId;
    /** Logical address, e.g. "R3C2" (1-based). Used as fallback when cellId fails. */
    String cellLogicalAddress;
}
