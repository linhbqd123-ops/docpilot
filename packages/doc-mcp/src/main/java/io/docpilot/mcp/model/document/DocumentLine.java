package io.docpilot.mcp.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Represents a single "line" in a document, where each line corresponds to one
 * block-level HTML element (p, h1-h6, li, td, th, etc.).
 *
 * <p>Line numbers are 1-based and assigned in document order via depth-first
 * traversal of the fidelity HTML, stopping at the first block-level element
 * encountered in each subtree.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentLine {

    /** 1-based position of this line in the document. */
    @JsonProperty("line_number")
    int lineNumber;

    /** Plain text content of this element (no HTML tags). */
    String text;

    /** Full outer HTML of this block element including its attributes. */
    @JsonProperty("outer_html")
    String outerHtml;

    /** HTML tag name, e.g. "p", "h2", "li", "td". */
    String tag;

    /** Value of the {@code data-doc-node-id} attribute, if present. */
    @JsonProperty("block_id")
    String blockId;
}
