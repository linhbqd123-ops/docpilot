package io.docpilot.mcp.model.diff;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** A style property change on a specific block or run. */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StyleDiffEntry {
    String blockId;
    String runId;
    /** Property name, e.g. "font", "fontSize", "color", "spacingAfter". */
    String property;
    String oldValue;
    String newValue;
}
