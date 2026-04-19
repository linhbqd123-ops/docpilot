package io.docpilot.mcp.personalization;

import io.docpilot.mcp.model.document.ContentProps;
import io.docpilot.mcp.model.document.DocumentComponent;

import java.util.ArrayList;
import java.util.List;

public final class DocumentTextSupport {

    private DocumentTextSupport() {
    }

    /**
     * Node types that act as containers — they should always derive their display text
     * from their children, not from their own contentProps.text (which may be stale
     * metadata set during import or by a previous patch).
     */
    private static final java.util.Set<io.docpilot.mcp.model.document.ComponentType> CONTAINER_TYPES =
        java.util.EnumSet.of(
            io.docpilot.mcp.model.document.ComponentType.DOCUMENT,
            io.docpilot.mcp.model.document.ComponentType.SECTION,
            io.docpilot.mcp.model.document.ComponentType.PARAGRAPH,
            io.docpilot.mcp.model.document.ComponentType.TABLE,
            io.docpilot.mcp.model.document.ComponentType.TABLE_ROW,
            io.docpilot.mcp.model.document.ComponentType.TABLE_CELL
        );

    public static String extractText(DocumentComponent node) {
        if (node == null) {
            return "";
        }

        // For container nodes, always build text from children so we don't return
        // stale metadata stored in contentProps.text (e.g. "Link" on DOCUMENT/SECTION).
        boolean isContainer = node.getType() != null && CONTAINER_TYPES.contains(node.getType());

        if (!isContainer) {
            // Leaf nodes (TEXT_RUN, HYPERLINK, IMAGE, …): use own text directly.
            String ownText = extractOwnText(node.getContentProps());
            if (!ownText.isBlank()) {
                return ownText;
            }
        }

        if (node.getChildren() == null || node.getChildren().isEmpty()) {
            // Container with no children — fall back to own text as last resort.
            return extractOwnText(node.getContentProps());
        }

        List<String> parts = new ArrayList<>();
        for (DocumentComponent child : node.getChildren()) {
            String childText = extractText(child);
            if (!childText.isBlank()) {
                parts.add(childText);
            }
        }
        String fromChildren = normaliseWhitespace(String.join(" ", parts));
        if (!fromChildren.isBlank()) {
            return fromChildren;
        }
        // Final fallback for containers with children that all have no text.
        return extractOwnText(node.getContentProps());
    }

    public static String normaliseWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String extractOwnText(ContentProps props) {
        if (props == null) {
            return "";
        }
        return firstNonBlank(
            props.getText(),
            props.getHyperlinkText(),
            props.getFieldResult(),
            props.getImageAltText()
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalised = normaliseWhitespace(value);
            if (!normalised.isBlank()) {
                return normalised;
            }
        }
        return "";
    }
}