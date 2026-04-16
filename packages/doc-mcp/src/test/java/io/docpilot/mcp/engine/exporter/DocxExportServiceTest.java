package io.docpilot.mcp.engine.exporter;

import io.docpilot.mcp.converter.HtmlToDocxConverter;
import io.docpilot.mcp.engine.fidelity.FidelityHtmlService;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.model.session.SessionState;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.storage.RegistryStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocxExportServiceTest {

    @Test
    void exportReturnsOriginalSnapshotWhenSessionHasNoAppliedRevision() {
        RecordingHtmlToDocxConverter converter = new RecordingHtmlToDocxConverter();
        InMemoryRegistryStore registryStore = new InMemoryRegistryStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        DocxExportService service = new DocxExportService(converter, registryStore, sessionStore);

        DocumentSession session = session("session-1", null);
        sessionStore.docxSnapshots.put("session-1", new byte[]{1, 2, 3});

        byte[] exported = service.export(session);

        assertArrayEquals(new byte[]{1, 2, 3}, exported);
        assertEquals(0, converter.convertCalls);
    }

    @Test
    void exportConvertsCurrentFidelitySourceHtmlForEditedSessions() {
        RecordingHtmlToDocxConverter converter = new RecordingHtmlToDocxConverter();
        InMemoryRegistryStore registryStore = new InMemoryRegistryStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        DocxExportService service = new DocxExportService(converter, registryStore, sessionStore);

        DocumentSession session = session("session-1", "rev-1");
        sessionStore.textAssets.put(
            "session-1::" + FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME,
            "<article><p>Fidelity source</p></article>"
        );

        byte[] exported = service.export(session);

        assertArrayEquals(new byte[]{9, 9, 9}, exported);
        assertEquals(1, converter.convertCalls);
        assertEquals("<article><p>Fidelity source</p></article>", converter.lastHtml);
    }

    @Test
    void exportFailsWhenFidelitySourceHtmlIsMissingForEditedSessions() {
        RecordingHtmlToDocxConverter converter = new RecordingHtmlToDocxConverter();
        InMemoryRegistryStore registryStore = new InMemoryRegistryStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        DocxExportService service = new DocxExportService(converter, registryStore, sessionStore);

        DocumentSession session = session("session-1", "rev-1");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.export(session));

        assertEquals("Fidelity source HTML is missing for session session-1. Export cannot continue.", error.getMessage());
        assertEquals(0, converter.convertCalls);
    }

    private static DocumentSession session(String sessionId, String currentRevisionId) {
        return DocumentSession.builder()
            .sessionId(sessionId)
            .docId("doc-1")
            .filename("example.docx")
            .originalFilename("example.docx")
            .currentRevisionId(currentRevisionId)
            .state(SessionState.READY)
            .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
            .lastModifiedAt(Instant.parse("2024-01-01T00:00:00Z"))
            .wordCount(100)
            .paragraphCount(4)
            .tableCount(0)
            .imageCount(0)
            .sectionCount(1)
            .build();
    }

    private static final class RecordingHtmlToDocxConverter extends HtmlToDocxConverter {
        private int convertCalls;
        private String lastHtml;

        @Override
        public byte[] convert(String html, String baseUrl, io.docpilot.mcp.model.legacy.StyleRegistry registry) {
            this.convertCalls += 1;
            this.lastHtml = html;
            return new byte[]{9, 9, 9};
        }
    }

    private static final class InMemoryRegistryStore extends RegistryStore {
        private InMemoryRegistryStore() {
            super(null, null, null);
        }

        @Override
        public Optional<io.docpilot.mcp.model.legacy.StyleRegistry> findRegistry(String docId) {
            return Optional.empty();
        }
    }

    private static final class InMemoryDocumentSessionStore extends DocumentSessionStore {
        private final Map<String, byte[]> docxSnapshots = new ConcurrentHashMap<>();
        private final Map<String, String> textAssets = new ConcurrentHashMap<>();

        private InMemoryDocumentSessionStore() {
            super(null, null, null);
        }

        @Override
        public Optional<byte[]> findDocxSnapshot(String sessionId) {
            return Optional.ofNullable(docxSnapshots.get(sessionId));
        }

        @Override
        public Optional<String> findTextAsset(String sessionId, String assetName) {
            return Optional.ofNullable(textAssets.get(sessionId + "::" + assetName));
        }
    }
}