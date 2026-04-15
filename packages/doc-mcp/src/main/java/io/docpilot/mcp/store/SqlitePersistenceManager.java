package io.docpilot.mcp.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.legacy.StyleRegistry;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.revision.ConflictRevision;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.session.DocumentSession;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqlitePersistenceManager {

    private static final String LEGACY_JSON_MIGRATION_KEY = "legacy-json-v1";

    private final AppProperties props;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void initialize() {
        applyPragmas();
        createSchema();
        migrateLegacyJsonIfNeeded();
        if (props.persistence().optimizeOnStartup()) {
            jdbcTemplate.execute("PRAGMA optimize");
        }
    }

    private void applyPragmas() {
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");
        jdbcTemplate.execute("PRAGMA journal_mode = WAL");
        jdbcTemplate.execute("PRAGMA synchronous = NORMAL");
        jdbcTemplate.execute("PRAGMA temp_store = MEMORY");
        jdbcTemplate.execute("PRAGMA mmap_size = " + props.persistence().mmapSizeBytes());
        jdbcTemplate.execute("PRAGMA wal_autocheckpoint = " + props.persistence().walAutocheckpointPages());
        jdbcTemplate.execute("PRAGMA journal_size_limit = " + props.persistence().journalSizeLimitBytes());
        jdbcTemplate.execute("PRAGMA cache_size = -" + props.persistence().cacheSizeKb());
    }

    private void createSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS registries (
                doc_id TEXT PRIMARY KEY,
                filename TEXT,
                extracted_at TEXT,
                payload_json TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS sessions (
                session_id TEXT PRIMARY KEY,
                doc_id TEXT NOT NULL,
                filename TEXT,
                original_filename TEXT,
                state TEXT,
                current_revision_id TEXT,
                created_at TEXT,
                last_modified_at TEXT,
                word_count INTEGER,
                paragraph_count INTEGER,
                table_count INTEGER,
                image_count INTEGER,
                section_count INTEGER,
                payload_json TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS session_binary_assets (
                session_id TEXT NOT NULL,
                asset_name TEXT NOT NULL,
                payload BLOB NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (session_id, asset_name),
                FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS patches (
                patch_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                base_revision_id TEXT,
                created_at TEXT,
                author TEXT,
                summary TEXT,
                working_set_json TEXT,
                payload_json TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS revisions (
                revision_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                base_revision_id TEXT,
                patch_id TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at TEXT,
                applied_at TEXT,
                summary TEXT,
                author TEXT,
                scope TEXT,
                payload_json TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE,
                FOREIGN KEY (patch_id) REFERENCES patches(patch_id) ON DELETE CASCADE
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conflicts (
                conflict_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                agent_patch_id TEXT,
                manual_revision_id TEXT,
                resolution TEXT,
                payload_json TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS snapshots (
                session_id TEXT NOT NULL,
                snapshot_id TEXT NOT NULL,
                revision_id TEXT,
                is_initial INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (session_id, snapshot_id),
                FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS legacy_migrations (
                migration_key TEXT PRIMARY KEY,
                completed_at TEXT NOT NULL,
                details_json TEXT NOT NULL
            )
            """);

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_sessions_doc_id ON sessions(doc_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_revisions_session_created ON revisions(session_id, created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_revisions_session_status_created ON revisions(session_id, status, created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_patches_session_created ON patches(session_id, created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_conflicts_session_updated ON conflicts(session_id, updated_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_session_revision ON snapshots(session_id, revision_id)");
    }

    private void migrateLegacyJsonIfNeeded() {
        if (!props.persistence().migrateLegacyJsonOnStartup()) {
            return;
        }

        Integer existing = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM legacy_migrations WHERE migration_key = ?",
            Integer.class,
            LEGACY_JSON_MIGRATION_KEY
        );
        if (existing != null && existing > 0) {
            return;
        }

        MigrationReport report = new MigrationReport();
        migrateRegistries(report);
        migrateSessions(report);
        migrateSessionSnapshots(report);
        migratePatches(report);
        migrateRevisions(report);
        migrateConflicts(report);
        migrateSnapshots(report);

        jdbcTemplate.update(
            "INSERT INTO legacy_migrations (migration_key, completed_at, details_json) VALUES (?, ?, ?)",
            LEGACY_JSON_MIGRATION_KEY,
            Instant.now().toString(),
            toJson(report)
        );

        if (props.persistence().archiveLegacyJson()) {
            archiveConfiguredLegacyDirectories();
        }

        log.info(
            "SQLite migration complete: registries={} sessions={} docxSnapshots={} revisions={} patches={} conflicts={} snapshots={} failures={}",
            report.registries,
            report.sessions,
            report.docxSnapshots,
            report.revisions,
            report.patches,
            report.conflicts,
            report.snapshots,
            report.failures
        );
    }

    private void migrateRegistries(MigrationReport report) {
        for (Path file : listFiles(candidateLegacyDirs(props.storage().registryPath(), Path.of("./data/registries")), "*.registry.json")) {
            try {
                StyleRegistry registry = objectMapper.readValue(file.toFile(), StyleRegistry.class);
                upsertRegistry(registry);
                report.registries++;
            } catch (IOException e) {
                report.failures++;
                log.warn("Cannot migrate registry {}: {}", file, e.getMessage());
            }
        }
    }

    private void migrateSessions(MigrationReport report) {
        for (Path file : listFiles(candidateLegacyDirs(props.storage().sessionsPath(), Path.of("./data/sessions")), "*.session.json")) {
            try {
                DocumentSession session = objectMapper.readValue(file.toFile(), DocumentSession.class);
                upsertSession(session);
                report.sessions++;
            } catch (IOException e) {
                report.failures++;
                log.warn("Cannot migrate session {}: {}", file, e.getMessage());
            }
        }
    }

    private void migrateSessionSnapshots(MigrationReport report) {
        for (Path sessionsDir : candidateLegacyDirs(props.storage().sessionsPath(), Path.of("./data/sessions"))) {
            if (!Files.isDirectory(sessionsDir)) {
                continue;
            }
            try (Stream<Path> directories = Files.list(sessionsDir)) {
                directories.filter(Files::isDirectory).forEach(directory -> {
                    Path docx = directory.resolve("original.docx");
                    if (!Files.exists(docx)) {
                        return;
                    }
                    try {
                        upsertBinaryAsset(directory.getFileName().toString(), "original.docx", Files.readAllBytes(docx));
                        report.docxSnapshots++;
                    } catch (IOException e) {
                        report.failures++;
                        log.warn("Cannot migrate DOCX snapshot {}: {}", docx, e.getMessage());
                    }
                });
            } catch (IOException e) {
                report.failures++;
                log.warn("Cannot scan session snapshot directory {}: {}", sessionsDir, e.getMessage());
            }
        }
    }

    private void migrateRevisions(MigrationReport report) {
        for (Path file : listFiles(candidateLegacyDirs(props.storage().revisionsPath(), Path.of("./data/revisions")), "*.revision.json")) {
            try {
                Revision revision = objectMapper.readValue(file.toFile(), Revision.class);
                upsertRevision(revision);
                report.revisions++;
            } catch (IOException e) {
                report.failures++;
                log.warn("Cannot migrate revision {}: {}", file, e.getMessage());
            }
        }
    }

    private void migratePatches(MigrationReport report) {
        for (Path file : listFiles(candidateLegacyDirs(props.storage().revisionsPath(), Path.of("./data/revisions")), "*.patch.json")) {
            try {
                Patch patch = objectMapper.readValue(file.toFile(), Patch.class);
                upsertPatch(patch.getPatchId(), patch);
                report.patches++;
            } catch (IOException e) {
                report.failures++;
                log.warn("Cannot migrate patch {}: {}", file, e.getMessage());
            }
        }
    }

    private void migrateConflicts(MigrationReport report) {
        for (Path file : listFiles(candidateLegacyDirs(props.storage().revisionsPath(), Path.of("./data/revisions")), "*.conflict.json")) {
            try {
                ConflictRevision conflict = objectMapper.readValue(file.toFile(), ConflictRevision.class);
                upsertConflict(conflict);
                report.conflicts++;
            } catch (IOException e) {
                report.failures++;
                log.warn("Cannot migrate conflict {}: {}", file, e.getMessage());
            }
        }
    }

    private void migrateSnapshots(MigrationReport report) {
        for (Path revisionsDir : candidateLegacyDirs(props.storage().revisionsPath(), Path.of("./data/revisions"))) {
            if (!Files.isDirectory(revisionsDir)) {
                continue;
            }

            try (Stream<Path> directories = Files.list(revisionsDir)) {
                directories.filter(Files::isDirectory).forEach(directory -> {
                    String sessionId = directory.getFileName().toString();
                    try (Stream<Path> snapshotFiles = Files.list(directory)) {
                        snapshotFiles
                            .filter(path -> path.getFileName().toString().endsWith(".snapshot.json"))
                            .forEach(file -> {
                                try {
                                    String filename = file.getFileName().toString();
                                    String snapshotId = filename.replace(".snapshot.json", "");
                                    String revisionId = "initial".equals(snapshotId) ? null : snapshotId;
                                    DocumentComponent snapshot = objectMapper.readValue(file.toFile(), DocumentComponent.class);
                                    upsertSnapshot(sessionId, revisionId, snapshot);
                                    report.snapshots++;
                                } catch (IOException e) {
                                    report.failures++;
                                    log.warn("Cannot migrate snapshot {}: {}", file, e.getMessage());
                                }
                            });
                    } catch (IOException e) {
                        report.failures++;
                        log.warn("Cannot scan snapshot directory {}: {}", directory, e.getMessage());
                    }
                });
            } catch (IOException e) {
                report.failures++;
                log.warn("Cannot scan legacy revision directory {}: {}", revisionsDir, e.getMessage());
            }
        }
    }

    private List<Path> candidateLegacyDirs(Path configured, Path fallback) {
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(configured.toAbsolutePath().normalize());
        candidates.add(fallback.toAbsolutePath().normalize());
        return new ArrayList<>(candidates);
    }

    private List<Path> listFiles(List<Path> directories, String glob) {
        List<Path> files = new ArrayList<>();
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(path -> matches(path, glob)).forEach(files::add);
            } catch (IOException e) {
                log.warn("Cannot scan directory {}: {}", directory, e.getMessage());
            }
        }
        return files;
    }

    private boolean matches(Path path, String glob) {
        return path.getFileSystem().getPathMatcher("glob:" + glob).matches(path.getFileName());
    }

    private void archiveConfiguredLegacyDirectories() {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(Instant.now().atZone(java.time.ZoneOffset.UTC));
        Path archiveRoot = props.storage().legacyArchivePath().resolve(stamp);

        archiveDirectory(props.storage().registryPath(), archiveRoot.resolve("registries"));
        archiveDirectory(props.storage().sessionsPath(), archiveRoot.resolve("sessions"));
        archiveDirectory(props.storage().revisionsPath(), archiveRoot.resolve("revisions"));
    }

    private void archiveDirectory(Path source, Path target) {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!Files.exists(normalizedSource)) {
            return;
        }

        try {
            Files.createDirectories(target.getParent());
            Files.move(normalizedSource, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Archived legacy directory {} -> {}", normalizedSource, target);
        } catch (IOException e) {
            log.warn("Cannot archive legacy directory {}: {}", normalizedSource, e.getMessage());
        }
    }

    private void upsertRegistry(StyleRegistry registry) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
            """
            INSERT INTO registries (doc_id, filename, extracted_at, payload_json, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(doc_id) DO UPDATE SET
                filename = excluded.filename,
                extracted_at = excluded.extracted_at,
                payload_json = excluded.payload_json,
                updated_at = excluded.updated_at
            """,
            registry.getDocId(),
            registry.getFilename(),
            registry.getExtractedAt() != null ? registry.getExtractedAt().toString() : null,
            toJson(registry),
            now
        );
    }

    private void upsertSession(DocumentSession session) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
            """
            INSERT INTO sessions (
                session_id, doc_id, filename, original_filename, state, current_revision_id,
                created_at, last_modified_at, word_count, paragraph_count, table_count, image_count,
                section_count, payload_json, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                doc_id = excluded.doc_id,
                filename = excluded.filename,
                original_filename = excluded.original_filename,
                state = excluded.state,
                current_revision_id = excluded.current_revision_id,
                created_at = excluded.created_at,
                last_modified_at = excluded.last_modified_at,
                word_count = excluded.word_count,
                paragraph_count = excluded.paragraph_count,
                table_count = excluded.table_count,
                image_count = excluded.image_count,
                section_count = excluded.section_count,
                payload_json = excluded.payload_json,
                updated_at = excluded.updated_at
            """,
            session.getSessionId(),
            session.getDocId(),
            session.getFilename(),
            session.getOriginalFilename(),
            session.getState() != null ? session.getState().name() : null,
            session.getCurrentRevisionId(),
            session.getCreatedAt() != null ? session.getCreatedAt().toString() : null,
            session.getLastModifiedAt() != null ? session.getLastModifiedAt().toString() : null,
            session.getWordCount(),
            session.getParagraphCount(),
            session.getTableCount(),
            session.getImageCount(),
            session.getSectionCount(),
            toJson(session),
            now
        );
    }

    private void upsertBinaryAsset(String sessionId, String assetName, byte[] payload) {
        jdbcTemplate.update(
            """
            INSERT INTO session_binary_assets (session_id, asset_name, payload, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(session_id, asset_name) DO UPDATE SET
                payload = excluded.payload,
                updated_at = excluded.updated_at
            """,
            sessionId,
            assetName,
            payload,
            Instant.now().toString()
        );
    }

    private void upsertPatch(String patchId, Patch patch) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
            """
            INSERT INTO patches (
                patch_id, session_id, base_revision_id, created_at, author, summary, working_set_json, payload_json, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(patch_id) DO UPDATE SET
                session_id = excluded.session_id,
                base_revision_id = excluded.base_revision_id,
                created_at = excluded.created_at,
                author = excluded.author,
                summary = excluded.summary,
                working_set_json = excluded.working_set_json,
                payload_json = excluded.payload_json,
                updated_at = excluded.updated_at
            """,
            patchId,
            patch.getSessionId(),
            patch.getBaseRevisionId(),
            patch.getCreatedAt() != null ? patch.getCreatedAt().toString() : null,
            patch.getAuthor(),
            patch.getSummary(),
            toJson(patch.getWorkingSet()),
            toJson(patch),
            now
        );
    }

    private void upsertRevision(Revision revision) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
            """
            INSERT INTO revisions (
                revision_id, session_id, base_revision_id, patch_id, status, created_at,
                applied_at, summary, author, scope, payload_json, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(revision_id) DO UPDATE SET
                session_id = excluded.session_id,
                base_revision_id = excluded.base_revision_id,
                patch_id = excluded.patch_id,
                status = excluded.status,
                created_at = excluded.created_at,
                applied_at = excluded.applied_at,
                summary = excluded.summary,
                author = excluded.author,
                scope = excluded.scope,
                payload_json = excluded.payload_json,
                updated_at = excluded.updated_at
            """,
            revision.getRevisionId(),
            revision.getSessionId(),
            revision.getBaseRevisionId(),
            revision.getPatchId(),
            revision.getStatus() != null ? revision.getStatus().name() : null,
            revision.getCreatedAt() != null ? revision.getCreatedAt().toString() : null,
            revision.getAppliedAt() != null ? revision.getAppliedAt().toString() : null,
            revision.getSummary(),
            revision.getAuthor(),
            revision.getScope(),
            toJson(revision),
            now
        );
    }

    private void upsertConflict(ConflictRevision conflict) {
        jdbcTemplate.update(
            """
            INSERT INTO conflicts (
                conflict_id, session_id, agent_patch_id, manual_revision_id, resolution, payload_json, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(conflict_id) DO UPDATE SET
                session_id = excluded.session_id,
                agent_patch_id = excluded.agent_patch_id,
                manual_revision_id = excluded.manual_revision_id,
                resolution = excluded.resolution,
                payload_json = excluded.payload_json,
                updated_at = excluded.updated_at
            """,
            conflict.getConflictId(),
            conflict.getSessionId(),
            conflict.getAgentPatchId(),
            conflict.getManualRevisionId(),
            conflict.getResolution(),
            toJson(conflict),
            Instant.now().toString()
        );
    }

    private void upsertSnapshot(String sessionId, String revisionId, DocumentComponent snapshot) {
        String snapshotId = revisionId == null || revisionId.isBlank() ? "initial" : revisionId;
        jdbcTemplate.update(
            """
            INSERT INTO snapshots (session_id, snapshot_id, revision_id, is_initial, payload_json, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(session_id, snapshot_id) DO UPDATE SET
                revision_id = excluded.revision_id,
                is_initial = excluded.is_initial,
                payload_json = excluded.payload_json,
                updated_at = excluded.updated_at
            """,
            sessionId,
            snapshotId,
            revisionId,
            revisionId == null || revisionId.isBlank() ? 1 : 0,
            toJson(snapshot),
            Instant.now().toString()
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize SQLite payload", e);
        }
    }

    private static final class MigrationReport {
        public int registries;
        public int sessions;
        public int docxSnapshots;
        public int revisions;
        public int patches;
        public int conflicts;
        public int snapshots;
        public int failures;
    }
}