package io.docpilot.mcp.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Three-layer stable anchor for a document component.
 *
 * <p>Resolution order (from plan section 8.4.1):
 * <ol>
 *   <li>{@code stableId} — primary key, UUID assigned at import time.</li>
 *   <li>{@code logicalPath} — re-locate node by structural position when UUID lookup fails.</li>
 *   <li>{@code structuralFingerprint} — rescue/remap when the tree has been normalised or merged.</li>
 * </ol>
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Anchor {

    /**
     * UUID assigned at import and stable for the entire session lifetime.
     * Example: {@code "a3f7c21d-4e8b-4b0f-9c2a-1234567890ab"}
     */
    String stableId;

    /**
     * Human-readable path within the document tree.
     * Example: {@code "section[2]/table[1]/row[3]/cell[2]/paragraph[1]"}
     */
    String logicalPath;

    /**
     * SHA-256 hex of {@code type|text_excerpt|styleRef|parentLogicalPath}.
     * Truncated to 16 hex chars for compactness.
     */
    String structuralFingerprint;

    // ── Table-specific sub-anchors (non-null only for TABLE_CELL nodes) ──

    /** Stable UUID of the containing table. */
    String tableId;

    /** Stable UUID of the containing row. */
    String rowId;

    /** Logical address within the table, e.g. {@code "R3C2"} (1-based). */
    String cellLogicalAddress;

    /** SHA-256 fingerprint of the owning table's structure. */
    String tableFingerprint;
}
