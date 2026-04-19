package io.docpilot.mcp.engine.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.engine.diff.DiffService;
import io.docpilot.mcp.converter.AnalysisHtmlConverter;
import io.docpilot.mcp.engine.fidelity.FidelityHtmlService;
import io.docpilot.mcp.engine.patch.PatchEngine;
import io.docpilot.mcp.exception.ConflictException;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.patch.PatchValidation;
import io.docpilot.mcp.model.revision.ConflictRevision;
import io.docpilot.mcp.model.revision.PendingRevisionPreview;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.revision.RevisionStatus;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.model.session.SessionState;
import io.docpilot.mcp.personalization.SemanticSearchMatch;
import io.docpilot.mcp.personalization.SemanticSearchService;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevisionServiceTest {

    @Test
    void applyPendingRevisionPersistsSnapshotAndAppliedStatus() {
        DocumentComponent appliedRoot = component("root-applied");
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(appliedRoot);
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RecordingSemanticSearchService semanticSearchService = new RecordingSemanticSearchService();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, semanticSearchService);

        DocumentComponent originalRoot = component("root-original");
        DocumentSession session = session("session-1", "rev-base", originalRoot);
        Revision revision = revision("rev-apply", "session-1", "rev-base", "patch-1", RevisionStatus.PENDING);
        Patch patch = patch("patch-1", "session-1", "rev-base", List.of("block-1"));

        revisionStore.revisions.put("rev-apply", revision);
        revisionStore.patches.put("patch-1", patch);
        seedCurrentSourceHtml(sessionStore, "session-1", "<article><p data-doc-node-id=\"block-1\">Original</p></article>");

        Revision applied = revisionService.apply("rev-apply", session);

        assertEquals(RevisionStatus.APPLIED, applied.getStatus());
        assertNotNull(applied.getAppliedAt());
        assertEquals("rev-apply", session.getCurrentRevisionId());
        assertSame(appliedRoot, session.getRoot());

        assertTrue(patchEngine.applyCalled);
        assertSame(patch, patchEngine.appliedPatch);
        assertSame(session, patchEngine.appliedSession);
        assertEquals("rev-apply", patchEngine.appliedRevisionId);
        assertEquals("agent", patchEngine.appliedAuthor);

        assertEquals(1, revisionStore.savedSnapshots.size());
        SavedSnapshot savedSnapshot = revisionStore.savedSnapshots.get(0);
        assertEquals("session-1", savedSnapshot.sessionId());
        assertEquals("rev-apply", savedSnapshot.revisionId());
        assertSame(appliedRoot, savedSnapshot.root());

        assertEquals(1, sessionStore.savedSessions.size());
        assertSame(session, sessionStore.savedSessions.get(0));
        assertEquals(List.of("session-1"), semanticSearchService.reindexedSessionIds);
        assertTrue(
            sessionStore.findTextAsset("session-1", FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME)
                .orElseThrow()
                .contains("Original")
        );
        assertTrue(sessionStore.findTextAsset("session-1", FidelityHtmlService.CURRENT_ANALYSIS_ASSET_NAME).isPresent());
        assertTrue(
            sessionStore.findTextAsset("session-1", FidelityHtmlService.revisionSourceSnapshotAssetName("rev-apply"))
                .orElseThrow()
                .contains("Original")
        );

        Revision savedRevision = revisionStore.savedRevisions.get(revisionStore.savedRevisions.size() - 1);
        assertEquals(RevisionStatus.APPLIED, savedRevision.getStatus());
        assertEquals("rev-apply", savedRevision.getRevisionId());
        assertNotNull(savedRevision.getAppliedAt());
    }

    @Test
    void applyCreatesConflictWithoutMutatingSessionWhenWorkingSetsOverlap() {
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(component("unused"));
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RecordingSemanticSearchService semanticSearchService = new RecordingSemanticSearchService();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, semanticSearchService);

        DocumentSession session = session("session-1", "rev-manual", component("root-current"));
        Revision revision = revision("rev-pending", "session-1", "rev-base", "patch-1", RevisionStatus.PENDING);
        Patch patch = patch("patch-1", "session-1", "rev-base", List.of("block-1", "block-9"));

        revisionStore.revisions.put("rev-pending", revision);
        revisionStore.patches.put("patch-1", patch);
        revisionStore.modifiedBlocks = List.of("block-1", "block-2");

        Revision result = revisionService.apply("rev-pending", session);

        assertEquals(RevisionStatus.CONFLICT, result.getStatus());
        assertEquals("rev-manual", session.getCurrentRevisionId());
        assertFalse(patchEngine.applyCalled);
        assertEquals(1, revisionStore.savedConflicts.size());
        assertEquals(List.of("block-1"), revisionStore.savedConflicts.get(0).getConflictingBlockIds());
        assertEquals(1, revisionStore.savedRevisions.size());
        assertEquals(RevisionStatus.CONFLICT, revisionStore.savedRevisions.get(0).getStatus());
        assertTrue(sessionStore.savedSessions.isEmpty());
        assertTrue(semanticSearchService.reindexedSessionIds.isEmpty());
    }

    @Test
    void rejectMarksRevisionRejectedWithoutSessionMutation() {
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(component("unused"));
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, new RecordingSemanticSearchService());

        Revision revision = revision("rev-reject", "session-1", "rev-base", "patch-1", RevisionStatus.PENDING);
        revisionStore.revisions.put("rev-reject", revision);

        Revision rejected = revisionService.reject("rev-reject");

        assertEquals(RevisionStatus.REJECTED, rejected.getStatus());
        assertEquals(1, revisionStore.savedRevisions.size());
        assertEquals(RevisionStatus.REJECTED, revisionStore.savedRevisions.get(0).getStatus());
        assertTrue(sessionStore.savedSessions.isEmpty());
        assertFalse(patchEngine.applyCalled);
    }

    @Test
    void rejectFailsForAppliedRevisions() {
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(component("unused"));
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, new RecordingSemanticSearchService());

        Revision revision = revision("rev-applied", "session-1", "rev-base", "patch-1", RevisionStatus.APPLIED);
        revisionStore.revisions.put("rev-applied", revision);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> revisionService.reject("rev-applied")
        );

        assertEquals("An applied revision cannot be rejected. Roll it back instead.", error.getMessage());
        assertTrue(revisionStore.savedRevisions.isEmpty());
        assertFalse(patchEngine.applyCalled);
    }

    @Test
    void rollbackRestoresBaseSnapshotAndPersistsSession() {
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(component("unused"));
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RecordingSemanticSearchService semanticSearchService = new RecordingSemanticSearchService();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, semanticSearchService);

        DocumentComponent currentRoot = component("root-current");
        DocumentComponent restoredRoot = component("root-restored");
        DocumentSession session = session("session-1", "rev-apply", currentRoot);
        Revision revision = revision("rev-apply", "session-1", "rev-base", "patch-1", RevisionStatus.APPLIED);

        revisionStore.revisions.put("rev-apply", revision);
        revisionStore.snapshots.put("session-1::rev-base", restoredRoot);
        seedRevisionSourceSnapshot(sessionStore, "session-1", "rev-base", "<article><p>Restored version</p></article>");

        Revision rolledBack = revisionService.rollback("rev-apply", session);

        assertEquals(RevisionStatus.REJECTED, rolledBack.getStatus());
        assertEquals("rev-base", session.getCurrentRevisionId());
        assertSame(restoredRoot, session.getRoot());
        assertEquals(1, sessionStore.savedSessions.size());
        assertSame(session, sessionStore.savedSessions.get(0));
        assertEquals(List.of("session-1"), semanticSearchService.reindexedSessionIds);
        assertEquals(1, revisionStore.savedRevisions.size());
        assertEquals(RevisionStatus.REJECTED, revisionStore.savedRevisions.get(0).getStatus());
        assertTrue(
            sessionStore.findTextAsset("session-1", FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME)
                .orElseThrow()
                .contains("Restored version")
        );
    }

    @Test
    void applyFailsBeforeMutatingSessionWhenCurrentFidelityHtmlIsMissing() {
        DocumentComponent originalRoot = component("root-original");
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(component("root-applied"));
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RecordingSemanticSearchService semanticSearchService = new RecordingSemanticSearchService();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, semanticSearchService);

        DocumentSession session = session("session-1", "rev-base", originalRoot);
        Revision revision = revision("rev-apply", "session-1", "rev-base", "patch-1", RevisionStatus.PENDING);
        Patch patch = patch("patch-1", "session-1", "rev-base", List.of("block-1"));

        revisionStore.revisions.put("rev-apply", revision);
        revisionStore.patches.put("patch-1", patch);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> revisionService.apply("rev-apply", session)
        );

        assertEquals("Current fidelity source HTML is missing for session session-1.", error.getMessage());
        assertFalse(patchEngine.applyCalled);
        assertSame(originalRoot, session.getRoot());
        assertEquals("rev-base", session.getCurrentRevisionId());
        assertTrue(sessionStore.savedSessions.isEmpty());
        assertTrue(semanticSearchService.reindexedSessionIds.isEmpty());
    }

    @Test
    void previewBuildsDiffAndFidelityHtmlWithoutMutatingSession() {
        DocumentComponent originalRoot = component("root-original");
        DocumentComponent appliedRoot = component("root-applied");
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(appliedRoot);
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RecordingSemanticSearchService semanticSearchService = new RecordingSemanticSearchService();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, semanticSearchService);

        DocumentSession session = session("session-1", "rev-base", originalRoot);
        Revision revision = revision("rev-preview", "session-1", "rev-base", "patch-1", RevisionStatus.PENDING);
        Patch patch = patch("patch-1", "session-1", "rev-base", List.of("block-1"));

        revisionStore.revisions.put("rev-preview", revision);
        revisionStore.patches.put("patch-1", patch);
        seedCurrentSourceHtml(sessionStore, "session-1", "<article><p data-doc-node-id=\"block-1\">Original</p></article>");

        PendingRevisionPreview preview = revisionService.preview("rev-preview", session);

        assertTrue(preview.isAvailable());
        assertEquals("rev-preview", preview.getRevisionId());
        assertEquals("session-1", preview.getSessionId());
        assertEquals("rev-base", preview.getCurrentRevisionId());
        assertNotNull(preview.getDiff());
        assertEquals("rev-preview", preview.getDiff().getTargetRevisionId());
        assertNotNull(preview.getHtml());
        assertTrue(preview.getHtml().contains("Original"));
        assertSame(originalRoot, session.getRoot());
        assertEquals("rev-base", session.getCurrentRevisionId());
        assertTrue(patchEngine.applyCalled);
        assertTrue(sessionStore.savedSessions.isEmpty());
        assertTrue(semanticSearchService.reindexedSessionIds.isEmpty());
    }

    @Test
    void rollbackFailsBeforeMutatingSessionWhenFidelitySnapshotIsMissing() {
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(component("unused"));
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RecordingSemanticSearchService semanticSearchService = new RecordingSemanticSearchService();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, semanticSearchService);

        DocumentComponent currentRoot = component("root-current");
        DocumentComponent restoredRoot = component("root-restored");
        DocumentSession session = session("session-1", "rev-apply", currentRoot);
        Revision revision = revision("rev-apply", "session-1", "rev-base", "patch-1", RevisionStatus.APPLIED);

        revisionStore.revisions.put("rev-apply", revision);
        revisionStore.snapshots.put("session-1::rev-base", restoredRoot);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> revisionService.rollback("rev-apply", session)
        );

        assertEquals("Fidelity source snapshot 'source.snapshot.rev-base.html' is missing for session session-1.", error.getMessage());
        assertSame(currentRoot, session.getRoot());
        assertEquals("rev-apply", session.getCurrentRevisionId());
        assertTrue(sessionStore.savedSessions.isEmpty());
        assertTrue(semanticSearchService.reindexedSessionIds.isEmpty());
    }

    @Test
    void rollbackRequiresTheCurrentAppliedRevision() {
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(component("unused"));
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, new RecordingSemanticSearchService());

        DocumentSession session = session("session-1", "rev-other", component("root-current"));
        Revision revision = revision("rev-apply", "session-1", "rev-base", "patch-1", RevisionStatus.APPLIED);
        revisionStore.revisions.put("rev-apply", revision);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> revisionService.rollback("rev-apply", session)
        );

        assertEquals("Only the current applied revision can be rolled back.", error.getMessage());
        assertTrue(sessionStore.savedSessions.isEmpty());
    }

    @Test
    void restoreReactivatesRolledBackCheckpointFromSnapshot() {
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(component("unused"));
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RecordingSemanticSearchService semanticSearchService = new RecordingSemanticSearchService();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, semanticSearchService);

        DocumentComponent currentRoot = component("root-current");
        DocumentComponent restoredRoot = component("root-restore-target");
        DocumentSession session = session("session-1", "rev-base", currentRoot);
        Revision revision = revision("rev-restore", "session-1", "rev-base", "patch-1", RevisionStatus.REJECTED)
            .toBuilder()
            .appliedAt(Instant.parse("2026-04-18T00:00:00Z"))
            .build();

        revisionStore.revisions.put("rev-restore", revision);
        revisionStore.snapshots.put("session-1::rev-restore", restoredRoot);
        seedRevisionSourceSnapshot(sessionStore, "session-1", "rev-restore", "<article><p>Redo version</p></article>");

        Revision restored = revisionService.restore("rev-restore", session);

        assertEquals(RevisionStatus.APPLIED, restored.getStatus());
        assertEquals("rev-restore", session.getCurrentRevisionId());
        assertSame(restoredRoot, session.getRoot());
        assertEquals(1, sessionStore.savedSessions.size());
        assertEquals(List.of("session-1"), semanticSearchService.reindexedSessionIds);
        assertEquals(1, revisionStore.savedRevisions.size());
        assertEquals(RevisionStatus.APPLIED, revisionStore.savedRevisions.get(0).getStatus());
        assertTrue(
            sessionStore.findTextAsset("session-1", FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME)
                .orElseThrow()
                .contains("Redo version")
        );
    }

    @Test
    void restoreRejectsDraftsThatWereNeverApplied() {
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(component("unused"));
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, new RecordingSemanticSearchService());

        DocumentSession session = session("session-1", "rev-base", component("root-current"));
        Revision revision = revision("rev-draft", "session-1", "rev-base", "patch-1", RevisionStatus.REJECTED);
        revisionStore.revisions.put("rev-draft", revision);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> revisionService.restore("rev-draft", session)
        );

        assertEquals("Only an applied checkpoint can be restored.", error.getMessage());
        assertEquals("rev-base", session.getCurrentRevisionId());
        assertTrue(sessionStore.savedSessions.isEmpty());
    }

    @Test
    void applyFailsForConflictRevisions() {
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(component("unused"));
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, new RecordingSemanticSearchService());

        DocumentSession session = session("session-1", "rev-current", component("root-current"));
        Revision revision = revision("rev-conflict", "session-1", "rev-base", "patch-1", RevisionStatus.CONFLICT);
        revisionStore.revisions.put("rev-conflict", revision);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> revisionService.apply("rev-conflict", session)
        );

        assertEquals("This revision is in conflict and cannot be applied.", error.getMessage());
        assertFalse(patchEngine.applyCalled);
        assertTrue(revisionStore.savedRevisions.isEmpty());
    }

    @Test
    void applyRejectsPendingRevisionWhenPatchNoLongerValid() {
        DocumentComponent originalRoot = component("root-original");
        RecordingPatchEngine patchEngine = new RecordingPatchEngine(
            component("unused"),
            List.of("Cannot locate target block=block-1")
        );
        InMemoryRevisionStore revisionStore = new InMemoryRevisionStore();
        InMemoryDocumentSessionStore sessionStore = new InMemoryDocumentSessionStore();
        RecordingSemanticSearchService semanticSearchService = new RecordingSemanticSearchService();
        RevisionService revisionService = revisionService(patchEngine, revisionStore, sessionStore, semanticSearchService);

        DocumentSession session = session("session-1", "rev-base", originalRoot);
        Revision revision = revision("rev-apply", "session-1", "rev-base", "patch-1", RevisionStatus.PENDING);
        Patch patch = patch("patch-1", "session-1", "rev-base", List.of("block-1"));

        revisionStore.revisions.put("rev-apply", revision);
        revisionStore.patches.put("patch-1", patch);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> revisionService.apply("rev-apply", session)
        );

        assertEquals("This revision can no longer be applied: Cannot locate target block=block-1", error.getMessage());
        assertFalse(patchEngine.applyCalled);
        assertSame(originalRoot, session.getRoot());
        assertEquals("rev-base", session.getCurrentRevisionId());
        assertEquals(1, revisionStore.savedRevisions.size());
        assertEquals(RevisionStatus.REJECTED, revisionStore.savedRevisions.get(0).getStatus());
        assertTrue(sessionStore.savedSessions.isEmpty());
        assertTrue(semanticSearchService.reindexedSessionIds.isEmpty());
    }

    private static RevisionService revisionService(
        PatchEngine patchEngine,
        InMemoryRevisionStore revisionStore,
        InMemoryDocumentSessionStore sessionStore,
        RecordingSemanticSearchService semanticSearchService
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new RevisionService(
            patchEngine,
            new DiffService(new AnchorService()),
            revisionStore,
            sessionStore,
            semanticSearchService,
            new FidelityHtmlService(),
            new AnalysisHtmlConverter(),
            objectMapper
        );
    }

    private static Revision revision(
        String revisionId,
        String sessionId,
        String baseRevisionId,
        String patchId,
        RevisionStatus status
    ) {
        return Revision.builder()
            .revisionId(revisionId)
            .sessionId(sessionId)
            .baseRevisionId(baseRevisionId)
            .patchId(patchId)
            .status(status)
            .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
            .summary("Summarized change")
            .author("agent")
            .scope("minor")
            .build();
    }

    private static Patch patch(
        String patchId,
        String sessionId,
        String baseRevisionId,
        List<String> workingSet
    ) {
        return Patch.builder()
            .patchId(patchId)
            .sessionId(sessionId)
            .baseRevisionId(baseRevisionId)
            .summary("Summarized change")
            .author("agent")
            .workingSet(workingSet)
            .operations(List.of())
            .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
            .build();
    }

    private static DocumentSession session(String sessionId, String currentRevisionId, DocumentComponent root) {
        return DocumentSession.builder()
            .sessionId(sessionId)
            .docId("doc-1")
            .filename("example.docx")
            .originalFilename("example.docx")
            .root(root)
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

    private static DocumentComponent component(String id) {
        return DocumentComponent.builder()
            .id(id)
            .type(ComponentType.DOCUMENT)
            .children(new ArrayList<>())
            .build();
    }

    private static void seedCurrentSourceHtml(InMemoryDocumentSessionStore sessionStore, String sessionId, String html) {
        sessionStore.saveTextAsset(sessionId, FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME, html);
    }

    private static void seedRevisionSourceSnapshot(InMemoryDocumentSessionStore sessionStore, String sessionId, String revisionId, String html) {
        sessionStore.saveTextAsset(sessionId, FidelityHtmlService.revisionSourceSnapshotAssetName(revisionId), html);
    }

    private record SavedSnapshot(String sessionId, String revisionId, DocumentComponent root) {}

    private static final class RecordingPatchEngine extends PatchEngine {
        private final DocumentComponent rootAfterApply;
        private final List<String> dryRunErrors;
        private boolean applyCalled;
        private Patch appliedPatch;
        private DocumentSession appliedSession;
        private String appliedRevisionId;
        private String appliedAuthor;

        private RecordingPatchEngine(DocumentComponent rootAfterApply) {
            this(rootAfterApply, List.of());
        }

        private RecordingPatchEngine(DocumentComponent rootAfterApply, List<String> dryRunErrors) {
            super(null, null);
            this.rootAfterApply = rootAfterApply;
            this.dryRunErrors = dryRunErrors;
        }

        @Override
        public PatchValidation dryRun(Patch patch, DocumentSession session) {
            return PatchValidation.builder()
                .structureOk(dryRunErrors.isEmpty())
                .styleOk(dryRunErrors.isEmpty())
                .errors(dryRunErrors)
                .warnings(List.of())
                .affectedBlockIds(List.of())
                .scope("minor")
                .build();
        }

        @Override
        public void apply(Patch patch, DocumentSession session, String revisionId, String author) {
            this.applyCalled = true;
            this.appliedPatch = patch;
            this.appliedSession = session;
            this.appliedRevisionId = revisionId;
            this.appliedAuthor = author;
            session.setRoot(rootAfterApply);
            session.setCurrentRevisionId(revisionId);
        }
    }

    private static final class InMemoryRevisionStore extends RevisionStore {
        private final Map<String, Revision> revisions = new ConcurrentHashMap<>();
        private final Map<String, Patch> patches = new ConcurrentHashMap<>();
        private final Map<String, DocumentComponent> snapshots = new ConcurrentHashMap<>();
        private final List<Revision> savedRevisions = new ArrayList<>();
        private final List<ConflictRevision> savedConflicts = new ArrayList<>();
        private final List<SavedSnapshot> savedSnapshots = new ArrayList<>();
        private List<String> modifiedBlocks = List.of();

        private InMemoryRevisionStore() {
            super(null, null, null);
        }

        @Override
        public void saveRevision(Revision revision) {
            revisions.put(revision.getRevisionId(), revision);
            savedRevisions.add(revision);
        }

        @Override
        public Optional<Revision> findRevision(String revisionId) {
            return Optional.ofNullable(revisions.get(revisionId));
        }

        @Override
        public void savePatch(String patchId, Patch patch) {
            patches.put(patchId, patch);
        }

        @Override
        public Optional<Patch> findPatch(String patchId) {
            return Optional.ofNullable(patches.get(patchId));
        }

        @Override
        public void saveConflict(ConflictRevision conflict) {
            savedConflicts.add(conflict);
        }

        @Override
        public void saveInitialSnapshot(String sessionId, DocumentComponent root) {
            snapshots.put(sessionId + "::initial", root);
        }

        @Override
        public void saveSnapshot(String sessionId, String revisionId, DocumentComponent root) {
            snapshots.put(snapshotKey(sessionId, revisionId), root);
            savedSnapshots.add(new SavedSnapshot(sessionId, revisionId, root));
        }

        @Override
        public Optional<DocumentComponent> findInitialSnapshot(String sessionId) {
            return Optional.ofNullable(snapshots.get(sessionId + "::initial"));
        }

        @Override
        public Optional<DocumentComponent> findSnapshot(String sessionId, String revisionId) {
            return Optional.ofNullable(snapshots.get(snapshotKey(sessionId, revisionId)));
        }

        @Override
        public List<String> collectModifiedBlocks(String sessionId, String sinceRevisionId, String untilRevisionId) {
            return modifiedBlocks;
        }

        private String snapshotKey(String sessionId, String revisionId) {
            return sessionId + "::" + revisionId;
        }
    }

    private static final class InMemoryDocumentSessionStore extends DocumentSessionStore {
        private final List<DocumentSession> savedSessions = new ArrayList<>();
        private final Map<String, String> textAssets = new ConcurrentHashMap<>();

        private InMemoryDocumentSessionStore() {
            super(null, null, null);
        }

        @Override
        public void save(DocumentSession session) {
            savedSessions.add(session);
        }

        @Override
        public void saveTextAsset(String sessionId, String assetName, String text) {
            textAssets.put(sessionId + "::" + assetName, text);
        }

        @Override
        public Optional<String> findTextAsset(String sessionId, String assetName) {
            return Optional.ofNullable(textAssets.get(sessionId + "::" + assetName));
        }
    }

    private static final class RecordingSemanticSearchService implements SemanticSearchService {
        private final List<String> reindexedSessionIds = new ArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public void reindexSession(DocumentSession session) {
            reindexedSessionIds.add(session.getSessionId());
        }

        @Override
        public List<SemanticSearchMatch> search(DocumentSession session, String query, int limit) {
            return List.of();
        }
    }
}