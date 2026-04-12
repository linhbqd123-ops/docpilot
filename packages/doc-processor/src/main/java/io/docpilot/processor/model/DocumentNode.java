package io.docpilot.processor.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * A node in the document's logical outline / content tree.
 *
 * <p>Types: "heading", "paragraph", "table", "list", "image", "section"
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentNode {

    /** Sequential identifier within the document. */
    String id;

    /** Content type: heading | paragraph | table | list | image | section */
    String type;

    /**
     * Heading level 1–9.  Null for non-heading nodes.
     */
    Integer level;

    /** Text content of the node (truncated to 500 chars for outline display). */
    String text;

    /** OOXML style ID applied to this element. */
    String styleId;

    /** Word count for this node. */
    Integer wordCount;

    /** Page number estimate (1-based). */
    Integer pageEstimate;

    /** Child nodes (e.g., sub-headings under a heading). */
    List<DocumentNode> children;
}
