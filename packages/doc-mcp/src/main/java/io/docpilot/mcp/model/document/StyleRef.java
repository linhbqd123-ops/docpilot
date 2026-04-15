package io.docpilot.mcp.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** Style reference attached to a component. */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StyleRef {
    /** OOXML style ID (e.g. "Heading1", "Normal"). */
    String styleId;
    /** Human-readable display name. */
    String styleName;
    /** Whether inline (character) formatting overrides the base style. */
    Boolean hasInlineOverrides;
}
