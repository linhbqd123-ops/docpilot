package io.docpilot.mcp.model.diff;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** A single text change (add / delete / replace) within a block. */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextDiffEntry {
    /** stableId of the affected block. */
    String blockId;
    /** "ADD" | "DELETE" | "REPLACE" */
    String changeType;
    String oldText;
    String newText;
    /** Character offset within the block where the change starts. */
    Integer offset;
}
