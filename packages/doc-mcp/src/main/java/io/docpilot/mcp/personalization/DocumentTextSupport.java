package io.docpilot.mcp.personalization;

import io.docpilot.mcp.model.document.ContentProps;
import io.docpilot.mcp.model.document.DocumentComponent;

import java.util.ArrayList;
import java.util.List;

public final class DocumentTextSupport {

    private DocumentTextSupport() {
    }

    public static String extractText(DocumentComponent node) {
        if (node == null) {
            return "";
        }

        String ownText = extractOwnText(node.getContentProps());
        if (!ownText.isBlank()) {
            return ownText;
        }

        if (node.getChildren() == null || node.getChildren().isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        for (DocumentComponent child : node.getChildren()) {
            String childText = extractText(child);
            if (!childText.isBlank()) {
                parts.add(childText);
            }
        }
        return normaliseWhitespace(String.join(" ", parts));
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