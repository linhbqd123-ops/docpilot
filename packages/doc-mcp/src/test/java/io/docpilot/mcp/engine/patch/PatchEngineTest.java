package io.docpilot.mcp.engine.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.ContentProps;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.document.StyleRef;
import io.docpilot.mcp.model.patch.OperationType;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.patch.PatchOperation;
import io.docpilot.mcp.model.patch.PatchTarget;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.model.session.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PatchEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PatchEngine patchEngine = new PatchEngine(new AnchorService(), objectMapper);

    @Test
    void replaceTextRangeKeepsTextRunsInSync() {
        DocumentComponent runOne = textRun("run-1", "paragraph-1", "Hello ", "style-normal");
        DocumentComponent runTwo = textRun("run-2", "paragraph-1", "World", "style-bold");
        DocumentComponent paragraph = DocumentComponent.builder()
            .id("paragraph-1")
            .type(ComponentType.PARAGRAPH)
            .contentProps(ContentProps.builder().text("Hello World").build())
            .children(new ArrayList<>(List.of(runOne, runTwo)))
            .build();
        DocumentSession session = sessionWith(paragraph);

        Patch patch = Patch.builder()
            .patchId("patch-1")
            .operations(List.of(
                PatchOperation.builder()
                    .op(OperationType.REPLACE_TEXT_RANGE)
                    .target(PatchTarget.builder().blockId("paragraph-1").start(6).end(11).build())
                    .value(objectMapper.valueToTree("DOCX"))
                    .build()
            ))
            .build();

        patchEngine.apply(patch, session, "rev-1", "ai");

        assertEquals("Hello DOCX", paragraph.getContentProps().getText());
        assertEquals(List.of("Hello ", "DOCX"), paragraph.getChildren().stream()
            .map(child -> child.getContentProps().getText())
            .toList());
        assertEquals("style-bold", paragraph.getChildren().get(1).getStyleRef().getStyleId());
    }

    @Test
    void cloneBlockDuplicatesStructuredContent() {
        DocumentComponent run = textRun("run-1", "paragraph-1", "Preserve me", "style-accent");
        DocumentComponent paragraph = DocumentComponent.builder()
            .id("paragraph-1")
            .type(ComponentType.PARAGRAPH)
            .parentId("root")
            .styleRef(StyleRef.builder().styleId("para-style").styleName("Paragraph Style").build())
            .contentProps(ContentProps.builder().text("Preserve me").build())
            .children(new ArrayList<>(List.of(run)))
            .build();
        DocumentComponent root = DocumentComponent.builder()
            .id("root")
            .type(ComponentType.DOCUMENT)
            .children(new ArrayList<>(List.of(paragraph)))
            .build();
        DocumentSession session = DocumentSession.builder()
            .sessionId("session-1")
            .docId("doc-1")
            .filename("sample.docx")
            .state(SessionState.READY)
            .createdAt(Instant.now())
            .lastModifiedAt(Instant.now())
            .root(root)
            .build();

        Patch patch = Patch.builder()
            .patchId("patch-2")
            .operations(List.of(
                PatchOperation.builder()
                    .op(OperationType.CLONE_BLOCK)
                    .target(PatchTarget.builder().blockId("paragraph-1").build())
                    .build()
            ))
            .build();

        patchEngine.apply(patch, session, "rev-2", "ai");

        assertEquals(2, root.getChildren().size());
        DocumentComponent clone = root.getChildren().get(1);
        assertNotEquals("paragraph-1", clone.getId());
        assertEquals("para-style", clone.getStyleRef().getStyleId());
        assertEquals("Preserve me", clone.getContentProps().getText());
        assertEquals(1, clone.getChildren().size());
        assertEquals("Preserve me", clone.getChildren().get(0).getContentProps().getText());
        assertEquals("style-accent", clone.getChildren().get(0).getStyleRef().getStyleId());
    }

    private static DocumentSession sessionWith(DocumentComponent paragraph) {
        DocumentComponent root = DocumentComponent.builder()
            .id("root")
            .type(ComponentType.DOCUMENT)
            .children(new ArrayList<>(List.of(paragraph)))
            .build();
        paragraph.setParentId("root");

        return DocumentSession.builder()
            .sessionId("session-1")
            .docId("doc-1")
            .filename("sample.docx")
            .state(SessionState.READY)
            .createdAt(Instant.now())
            .lastModifiedAt(Instant.now())
            .root(root)
            .build();
    }

    private static DocumentComponent textRun(String id, String parentId, String text, String styleId) {
        return DocumentComponent.builder()
            .id(id)
            .type(ComponentType.TEXT_RUN)
            .parentId(parentId)
            .styleRef(StyleRef.builder().styleId(styleId).build())
            .contentProps(ContentProps.builder().text(text).build())
            .children(new ArrayList<>())
            .build();
    }
}