package io.docpilot.mcp.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.engine.diff.DiffService;
import com.zaxxer.hikari.HikariDataSource;
import io.docpilot.mcp.config.AppConfig;
import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.converter.AnalysisHtmlConverter;
import io.docpilot.mcp.engine.fidelity.FidelityHtmlService;
import io.docpilot.mcp.engine.patch.PatchEngine;
import io.docpilot.mcp.engine.revision.RevisionService;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.legacy.StyleRegistry;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.patch.PatchValidation;
import io.docpilot.mcp.model.revision.ConflictRevision;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.revision.RevisionStatus;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.model.session.SessionState;
import io.docpilot.mcp.personalization.SemanticSearchMatch;
import io.docpilot.mcp.personalization.SemanticSearchService;
import io.docpilot.mcp.storage.RegistryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStorePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsSessionsRevisionsAndRegistryAcrossStoreReloads() throws Exception {
        AppProperties props = props(tempDir, false, false);

        try (StoreContext first = new StoreContext(props)) {
            first.registryStore.saveRegistry(registry("doc-1"));
            first.sessionStore.save(session("session-1", "doc-1", "rev-base"));
            first.sessionStore.saveDocxSnapshot("session-1", new byte[] {1, 2, 3, 4});
            first.revisionStore.savePatch("patch-1", patch("patch-1", "session-1", "rev-base"));
            first.revisionStore.saveRevision(revision("rev-1", "session-1", "rev-base", "patch-1", RevisionStatus.APPLIED));
            first.revisionStore.saveConflict(conflict("conflict-1", "session-1", "patch-1", "rev-1"));
            first.revisionStore.saveInitialSnapshot("session-1", component("root-initial"));
            first.revisionStore.saveSnapshot("session-1", "rev-1", component("root-rev-1"));
        }

        try (StoreContext second = new StoreContext(props)) {
            assertEquals("contract.docx", second.registryStore.findRegistry("doc-1").orElseThrow().getFilename());
            assertEquals("session-1", second.sessionStore.find("session-1").orElseThrow().getSessionId());
            assertArrayEquals(new byte[] {1, 2, 3, 4}, second.sessionStore.findDocxSnapshot("session-1").orElseThrow());
            assertEquals("patch-1", second.revisionStore.findPatch("patch-1").orElseThrow().getPatchId());
            assertEquals(RevisionStatus.APPLIED, second.revisionStore.findRevision("rev-1").orElseThrow().getStatus());
            assertEquals("conflict-1", second.revisionStore.findConflict("conflict-1").orElseThrow().getConflictId());
            assertEquals("root-initial", second.revisionStore.findInitialSnapshot("session-1").orElseThrow().getId());
            assertEquals("root-rev-1", second.revisionStore.findSnapshot("session-1", "rev-1").orElseThrow().getId());
            assertEquals(1, second.revisionStore.findBySession("session-1").size());
        }
    }

    @Test
    void migratesLegacyJsonFilesIntoSqlite() throws Exception {
        AppProperties props = props(tempDir, true, false);
        Path registryFile = props.storage().registryPath().resolve("doc-legacy.registry.json");
        Path sessionFile = props.storage().sessionsPath().resolve("session-legacy.session.json");
        Path patchFile = props.storage().revisionsPath().resolve("patch-legacy.patch.json");
        Path revisionFile = props.storage().revisionsPath().resolve("rev-legacy.revision.json");
        Path conflictFile = props.storage().revisionsPath().resolve("conflict-legacy.conflict.json");
        Path snapshotFile = props.storage().revisionsPath().resolve("session-legacy").resolve("initial.snapshot.json");
        Path docxSnapshot = props.storage().sessionsPath().resolve("session-legacy").resolve("original.docx");

        ObjectMapper mapper = new AppConfig().objectMapper();
        Files.createDirectories(registryFile.getParent());
        Files.createDirectories(sessionFile.getParent());
        Files.createDirectories(snapshotFile.getParent());
        Files.createDirectories(docxSnapshot.getParent());

        mapper.writeValue(registryFile.toFile(), registry("doc-legacy"));
        mapper.writeValue(sessionFile.toFile(), session("session-legacy", "doc-legacy", "rev-legacy"));
        mapper.writeValue(patchFile.toFile(), patch("patch-legacy", "session-legacy", null));
        mapper.writeValue(revisionFile.toFile(), revision("rev-legacy", "session-legacy", null, "patch-legacy", RevisionStatus.APPLIED));
        mapper.writeValue(conflictFile.toFile(), conflict("conflict-legacy", "session-legacy", "patch-legacy", "rev-legacy"));
        mapper.writeValue(snapshotFile.toFile(), component("legacy-root"));
        Files.write(docxSnapshot, new byte[] {9, 8, 7});

        try (StoreContext context = new StoreContext(props)) {
            assertEquals("doc-legacy", context.registryStore.findRegistry("doc-legacy").orElseThrow().getDocId());
            assertEquals("session-legacy", context.sessionStore.find("session-legacy").orElseThrow().getSessionId());
            assertEquals("patch-legacy", context.revisionStore.findPatch("patch-legacy").orElseThrow().getPatchId());
            assertEquals("rev-legacy", context.revisionStore.findRevision("rev-legacy").orElseThrow().getRevisionId());
            assertEquals("conflict-legacy", context.revisionStore.findConflict("conflict-legacy").orElseThrow().getConflictId());
            assertEquals("legacy-root", context.revisionStore.findInitialSnapshot("session-legacy").orElseThrow().getId());
            assertArrayEquals(new byte[] {9, 8, 7}, context.sessionStore.findDocxSnapshot("session-legacy").orElseThrow());
        }
    }

    @Test
    void deletingSessionCascadesBinaryAssets() throws Exception {
        AppProperties props = props(tempDir, false, false);

        try (StoreContext context = new StoreContext(props)) {
            context.sessionStore.save(session("session-delete", "doc-delete", null));
            context.sessionStore.saveDocxSnapshot("session-delete", new byte[] {5, 6, 7});

            context.sessionStore.delete("session-delete");

            assertTrue(context.sessionStore.find("session-delete").isEmpty());
            assertFalse(context.sessionStore.findDocxSnapshot("session-delete").isPresent());
        }
    }

    @Test
    void stagingRevisionPersistsPatchBeforeRevisionInSqlite() throws Exception {
        AppProperties props = props(tempDir, false, false);

        try (StoreContext context = new StoreContext(props)) {
            DocumentSession session = session("session-stage", "doc-stage", null);
            context.sessionStore.save(session);

            RevisionService revisionService = new RevisionService(
                new AcceptingPatchEngine(),
                new DiffService(new AnchorService()),
                context.revisionStore,
                context.sessionStore,
                new NoOpSemanticSearchService(),
                new FidelityHtmlService(),
                new AnalysisHtmlConverter(),
                new AppConfig().objectMapper()
            );

            Patch patch = patch("patch-stage", "session-stage", null);
            Revision staged = revisionService.stage(patch, session, "agent");

            assertEquals("patch-stage", context.revisionStore.findPatch("patch-stage").orElseThrow().getPatchId());
            assertEquals(staged.getRevisionId(), context.revisionStore.findRevision(staged.getRevisionId()).orElseThrow().getRevisionId());
            assertEquals(RevisionStatus.PENDING, staged.getStatus());
            assertEquals(1, context.revisionStore.findBySession("session-stage").size());
        }
    }

    private static AppProperties props(Path root, boolean migrateLegacyJson, boolean archiveLegacyJson) {
        return new AppProperties(
            new AppProperties.Storage(
                root.resolve("data").toString(),
                root.resolve("data/uploads").toString(),
                root.resolve("data/registries").toString(),
                root.resolve("data/sessions").toString(),
                root.resolve("data/revisions").toString(),
                root.resolve("data/legacy-archive").toString()
            ),
            new AppProperties.Persistence(
                root.resolve("data/sqlite/doc-mcp.db").toString(),
                2,
                5000,
                32L * 1024 * 1024,
                8192,
                1000,
                16L * 1024 * 1024,
                migrateLegacyJson,
                archiveLegacyJson,
                false
            ),
            new AppProperties.Personalization(
                "disabled",
                "http://127.0.0.1:6333",
                "docpilot_personalization",
                1536,
                "",
                "hashing",
                "",
                "",
                "",
                15000,
                8,
                1200
            ),
            new AppProperties.Processing(104857600L, true),
            new AppProperties.Mcp("doc-mcp", "2.0.0", "2024-11-05")
        );
    }

    private static StyleRegistry registry(String docId) {
        return StyleRegistry.builder()
            .docId(docId)
            .filename("contract.docx")
            .extractedAt(Instant.parse("2024-01-01T00:00:00Z"))
            .styles(java.util.Map.of())
            .customStyles(List.of())
            .numberingDefinitions(List.of())
            .defaultFontAscii("Calibri")
            .defaultFontSizePt(11.0)
            .defaultFontColor("000000")
            .build();
    }

    private static DocumentSession session(String sessionId, String docId, String currentRevisionId) {
        return DocumentSession.builder()
            .sessionId(sessionId)
            .docId(docId)
            .filename("contract.docx")
            .originalFilename("contract.docx")
            .root(component("root-" + sessionId))
            .currentRevisionId(currentRevisionId)
            .state(SessionState.READY)
            .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
            .lastModifiedAt(Instant.parse("2024-01-01T00:00:00Z"))
            .wordCount(120)
            .paragraphCount(6)
            .tableCount(1)
            .imageCount(0)
            .sectionCount(2)
            .build();
    }

    private static Patch patch(String patchId, String sessionId, String baseRevisionId) {
        return Patch.builder()
            .patchId(patchId)
            .sessionId(sessionId)
            .baseRevisionId(baseRevisionId)
            .summary("Add a clause")
            .author("agent")
            .workingSet(List.of("block-1", "block-2"))
            .operations(List.of())
            .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
            .build();
    }

    private static Revision revision(String revisionId, String sessionId, String baseRevisionId, String patchId, RevisionStatus status) {
        return Revision.builder()
            .revisionId(revisionId)
            .sessionId(sessionId)
            .baseRevisionId(baseRevisionId)
            .patchId(patchId)
            .status(status)
            .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
            .appliedAt(status == RevisionStatus.APPLIED ? Instant.parse("2024-01-01T00:01:00Z") : null)
            .summary("Apply contract edit")
            .author("agent")
            .scope("minor")
            .build();
    }

    private static ConflictRevision conflict(String conflictId, String sessionId, String patchId, String revisionId) {
        return ConflictRevision.builder()
            .conflictId(conflictId)
            .sessionId(sessionId)
            .agentPatchId(patchId)
            .manualRevisionId(revisionId)
            .conflictingBlockIds(List.of("block-1"))
            .agentChangeSummary("Agent update")
            .manualChangeSummary("Manual update")
            .resolution("MANUAL_MERGE")
            .build();
    }

    private static DocumentComponent component(String id) {
        return DocumentComponent.builder()
            .id(id)
            .type(ComponentType.DOCUMENT)
            .children(new ArrayList<>())
            .build();
    }

    private static final class StoreContext implements AutoCloseable {
        private final HikariDataSource dataSource;
        private final RegistryStore registryStore;
        private final DocumentSessionStore sessionStore;
        private final RevisionStore revisionStore;

        private StoreContext(AppProperties props) throws Exception {
            AppConfig appConfig = new AppConfig();
            ObjectMapper objectMapper = appConfig.objectMapper();
            DataSource source = appConfig.dataSource(props);
            JdbcTemplate jdbcTemplate = appConfig.jdbcTemplate(source);

            SqlitePersistenceManager persistenceManager = new SqlitePersistenceManager(props, jdbcTemplate, objectMapper);
            persistenceManager.initialize();

            this.dataSource = (HikariDataSource) source;
            this.registryStore = new RegistryStore(props, objectMapper, jdbcTemplate);
            this.sessionStore = new DocumentSessionStore(props, objectMapper, jdbcTemplate);
            this.revisionStore = new RevisionStore(props, objectMapper, jdbcTemplate);
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }

    private static final class AcceptingPatchEngine extends PatchEngine {
        private AcceptingPatchEngine() {
            super(null, null);
        }

        @Override
        public PatchValidation dryRun(Patch patch, DocumentSession session) {
            return PatchValidation.builder()
                .structureOk(true)
                .styleOk(true)
                .errors(List.of())
                .warnings(List.of())
                .affectedBlockIds(List.of())
                .scope("minor")
                .build();
        }
    }

    private static final class NoOpSemanticSearchService implements SemanticSearchService {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public void reindexSession(DocumentSession session) {
            // no-op for persistence testing
        }

        @Override
        public List<SemanticSearchMatch> search(DocumentSession session, String query, int limit) {
            return List.of();
        }
    }
}