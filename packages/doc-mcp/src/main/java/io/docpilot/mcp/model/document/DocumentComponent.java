package io.docpilot.mcp.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.ArrayList;
import java.util.List;

/**
 * The canonical document component — a node in the DOCX component tree.
 *
 * <p>Every node has a stable three-layer {@link Anchor}, a {@link ComponentType},
 * style reference, layout and content properties, and optional children.
 *
 * <p>Uses {@link Data} (mutable) rather than {@link lombok.Value} so that
 * {@link io.docpilot.mcp.engine.patch.PatchEngine} can apply in-place mutations
 * during patch operations.
 */
@Data
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentComponent {

    /** Stable UUID for this node (from stableId in anchor). */
    String id;

    ComponentType type;

    /** Id of the parent component. Null for the root DOCUMENT node. */
    String parentId;

    /** Ordered child components. */
    @Builder.Default
    List<DocumentComponent> children = new ArrayList<>();

    /** Style reference (OOXML style ID + display name). */
    StyleRef styleRef;

    /** Layout / formatting properties. */
    LayoutProps layoutProps;

    /** Text / content properties (primarily for leaf nodes). */
    ContentProps contentProps;

    /** Three-layer stable anchor. */
    Anchor anchor;

    /** Revision tracking metadata. */
    RevisionMetadata revisionMetadata;
}
