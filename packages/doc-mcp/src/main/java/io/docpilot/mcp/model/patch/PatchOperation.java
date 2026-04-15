package io.docpilot.mcp.model.patch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Single atomic operation within a {@link Patch}.
 *
 * <p>{@code value} is a flexible JSON node that holds the new value / arguments
 * for the operation.  Interpretation depends on {@code op}:
 *
 * <ul>
 *   <li>{@code REPLACE_TEXT_RANGE}: {@code value} is a JSON string.</li>
 *   <li>{@code APPLY_STYLE}: {@code value} is {@code {"styleId": "Heading1"}}.</li>
 *   <li>{@code APPLY_INLINE_FORMAT}: {@code value} is {@code {"bold": true, "color": "FF0000"}}.</li>
 *   <li>{@code CREATE_BLOCK}: {@code value} is a serialised {@link io.docpilot.mcp.model.document.DocumentComponent}.</li>
 * </ul>
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PatchOperation {

    OperationType op;
    PatchTarget target;
    /** New value / arguments.  Semantics depend on {@code op}. */
    JsonNode value;
    /** Human-readable description of this specific operation. */
    String description;
}
