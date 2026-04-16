package io.docpilot.mcp.engine.projection;

import io.docpilot.mcp.converter.HtmlToDocxConverter;
import io.docpilot.mcp.model.document.Anchor;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.ContentProps;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.document.LayoutProps;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.model.session.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlProjectionServiceTest {

    @Test
    void projectFragmentWrapsListItemsAndExportsToDocx() {
        HtmlProjectionService projectionService = new HtmlProjectionService();
        HtmlToDocxConverter converter = new HtmlToDocxConverter();

        DocumentComponent firstListItem = listItem("list-1", "First bullet", "BULLET", 0);
        DocumentComponent nestedListItem = listItem("list-1-1", "Nested number", "DECIMAL", 1);
        DocumentComponent secondListItem = listItem("list-2", "Second bullet", "BULLET", 0);

        DocumentComponent root = component(
            "root",
            ComponentType.DOCUMENT,
            List.of(
                paragraph("para-1", "Before list"),
                firstListItem,
                nestedListItem,
                secondListItem,
                paragraph("para-2", "After list")
            )
        );

        DocumentSession session = DocumentSession.builder()
            .sessionId("session-list-export")
            .docId("doc-list-export")
            .filename("list.docx")
            .originalFilename("list.docx")
            .root(root)
            .state(SessionState.READY)
            .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
            .lastModifiedAt(Instant.parse("2024-01-01T00:00:00Z"))
            .build();

        String html = projectionService.projectFragment(session);

        assertTrue(html.contains("<ul>"));
        assertTrue(html.contains("<ol>"));
        assertFalse(html.contains("<div class=\"doc-canvas\" data-doc-node-id=\"root\" data-anchor=\"root\" data-doc-node-type=\"document\">\n<li"));

        byte[] docx = converter.convert(html, null, null);
        assertTrue(docx.length > 0);
    }

    private static DocumentComponent paragraph(String id, String text) {
        return component(
            id,
            ComponentType.PARAGRAPH,
            List.of(textRun(id + "-run", text))
        );
    }

    private static DocumentComponent listItem(String id, String text, String listType, int level) {
        return DocumentComponent.builder()
            .id(id)
            .type(ComponentType.LIST_ITEM)
            .layoutProps(LayoutProps.builder().listType(listType).listLevel(level).build())
            .contentProps(ContentProps.builder().text(text).build())
            .anchor(anchor(id))
            .children(List.of(textRun(id + "-run", text)))
            .build();
    }

    private static DocumentComponent textRun(String id, String text) {
        return DocumentComponent.builder()
            .id(id)
            .type(ComponentType.TEXT_RUN)
            .contentProps(ContentProps.builder().text(text).build())
            .anchor(anchor(id))
            .children(List.of())
            .build();
    }

    private static DocumentComponent component(String id, ComponentType type, List<DocumentComponent> children) {
        return DocumentComponent.builder()
            .id(id)
            .type(type)
            .anchor(anchor(id))
            .children(children)
            .build();
    }

    private static Anchor anchor(String id) {
        return Anchor.builder()
            .stableId(id)
            .logicalPath("/" + id)
            .structuralFingerprint(id)
            .build();
    }
}