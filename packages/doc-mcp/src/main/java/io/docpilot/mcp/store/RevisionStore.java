package io.docpilot.mcp.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.revision.ConflictRevision;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.revision.RevisionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store for {@link Revision}, {@link Patch}, and {@link ConflictRevision} objects.
 */
@Repository
@Slf4j
public class RevisionStore {

    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private final ConcurrentHashMap<String, Revision>         revisionCache  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Patch>            patchCache     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConflictRevision> conflictCache  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DocumentComponent> snapshotCache = new ConcurrentHashMap<>();

    public RevisionStore(AppProperties props, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Revisions ──────────────────────────────────────────────────────────

    public void saveRevision(Revision revision) {
        revisionCache.put(revision.getRevisionId(), revision);
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

    public Optional<Revision> findRevision(String revisionId) {
        Revision cached = revisionCache.get(revisionId);
        if (cached != null) return Optional.of(cached);
        return jdbcTemplate.query(
            "SELECT payload_json FROM revisions WHERE revision_id = ?",
            rs -> {
                if (!rs.next()) {
                    return Optional.<Revision>empty();
                }
                try {
                    Revision loaded = objectMapper.readValue(rs.getString("payload_json"), Revision.class);
                    revisionCache.put(revisionId, loaded);
                    return Optional.of(loaded);
                } catch (IOException e) {
                    log.warn("Cannot deserialise revision {} from SQLite: {}", revisionId, e.getMessage());
                    return Optional.<Revision>empty();
                }
            },
            revisionId
        );
    }

    public List<Revision> findBySession(String sessionId) {
        return jdbcTemplate.query(
            "SELECT payload_json FROM revisions WHERE session_id = ? ORDER BY created_at DESC",
            (rs, rowNum) -> {
                try {
                    Revision revision = objectMapper.readValue(rs.getString("payload_json"), Revision.class);
                    revisionCache.put(revision.getRevisionId(), revision);
                    return revision;
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot deserialise revision payload", e);
                }
            },
            sessionId
        );
    }

    // ── Patches ─────────────────────────────────────────────────────────────

    public void savePatch(String patchId, Patch patch) {
        patchCache.put(patchId, patch);
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

    public Optional<Patch> findPatch(String patchId) {
        Patch cached = patchCache.get(patchId);
        if (cached != null) return Optional.of(cached);
        return jdbcTemplate.query(
            "SELECT payload_json FROM patches WHERE patch_id = ?",
            rs -> {
                if (!rs.next()) {
                    return Optional.<Patch>empty();
                }
                try {
                    Patch loaded = objectMapper.readValue(rs.getString("payload_json"), Patch.class);
                    patchCache.put(patchId, loaded);
                    return Optional.of(loaded);
                } catch (IOException e) {
                    log.warn("Cannot deserialise patch {} from SQLite: {}", patchId, e.getMessage());
                    return Optional.<Patch>empty();
                }
            },
            patchId
        );
    }

    // ── Conflicts ───────────────────────────────────────────────────────────

    public void saveConflict(ConflictRevision conflict) {
        conflictCache.put(conflict.getConflictId(), conflict);
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

    public Optional<ConflictRevision> findConflict(String conflictId) {
        ConflictRevision cached = conflictCache.get(conflictId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return jdbcTemplate.query(
            "SELECT payload_json FROM conflicts WHERE conflict_id = ?",
            rs -> {
                if (!rs.next()) {
                    return Optional.<ConflictRevision>empty();
                }
                try {
                    ConflictRevision loaded = objectMapper.readValue(rs.getString("payload_json"), ConflictRevision.class);
                    conflictCache.put(conflictId, loaded);
                    return Optional.of(loaded);
                } catch (IOException e) {
                    log.warn("Cannot deserialise conflict {} from SQLite: {}", conflictId, e.getMessage());
                    return Optional.<ConflictRevision>empty();
                }
            },
            conflictId
        );
    }

    // ── Snapshots ───────────────────────────────────────────────────────────

    public void saveInitialSnapshot(String sessionId, DocumentComponent root) {
        saveSnapshot(sessionId, null, root);
    }

    public void saveSnapshot(String sessionId, String revisionId, DocumentComponent root) {
        String key = snapshotKey(sessionId, revisionId);
        DocumentComponent clone = cloneTree(root);
        snapshotCache.put(key, clone);
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
            toJson(clone),
            Instant.now().toString()
        );
    }

    public Optional<DocumentComponent> findInitialSnapshot(String sessionId) {
        return findSnapshot(sessionId, null);
    }

    public Optional<DocumentComponent> findSnapshot(String sessionId, String revisionId) {
        String key = snapshotKey(sessionId, revisionId);
        DocumentComponent cached = snapshotCache.get(key);
        if (cached != null) {
            return Optional.of(cloneTree(cached));
        }

        String snapshotId = revisionId == null || revisionId.isBlank() ? "initial" : revisionId;
        return jdbcTemplate.query(
            "SELECT payload_json FROM snapshots WHERE session_id = ? AND snapshot_id = ?",
            rs -> {
                if (!rs.next()) {
                    return Optional.<DocumentComponent>empty();
                }
                try {
                    DocumentComponent loaded = objectMapper.readValue(rs.getString("payload_json"), DocumentComponent.class);
                    snapshotCache.put(key, loaded);
                    return Optional.of(cloneTree(loaded));
                } catch (IOException e) {
                    log.warn("Cannot deserialize snapshot {} from SQLite: {}", key, e.getMessage());
                    return Optional.<DocumentComponent>empty();
                }
            },
            sessionId,
            snapshotId
        );
    }

    // ── Concurrency helpers ─────────────────────────────────────────────────

    /**
     * Collects all block stableIds modified by revisions applied to {@code sessionId}
     * after {@code sinceRevisionId} up to (and including) {@code untilRevisionId}.
     *
     * <p>Used by the optimistic rebase algorithm in
     * {@link io.docpilot.mcp.engine.revision.RevisionService}.
     */
    public List<String> collectModifiedBlocks(String sessionId,
                                               String sinceRevisionId,
                                               String untilRevisionId) {
        List<String> modified = new ArrayList<>();
        boolean collecting    = sinceRevisionId == null || sinceRevisionId.isBlank();

        // Walk revision chain in chronological order
        List<Revision> sessionRevisions = jdbcTemplate.query(
            "SELECT payload_json FROM revisions WHERE session_id = ? AND status = ? ORDER BY COALESCE(applied_at, created_at) ASC",
            (rs, rowNum) -> {
                try {
                    Revision revision = objectMapper.readValue(rs.getString("payload_json"), Revision.class);
                    revisionCache.put(revision.getRevisionId(), revision);
                    return revision;
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot deserialize revision payload", e);
                }
            },
            sessionId,
            RevisionStatus.APPLIED.name()
        ).stream()
            .filter(r -> r.getStatus() == RevisionStatus.APPLIED)
            .sorted(Comparator.comparing(r -> Optional.ofNullable(r.getAppliedAt()).orElse(r.getCreatedAt())))
            .toList();

        for (Revision rev : sessionRevisions) {
            if (rev.getRevisionId().equals(sinceRevisionId)) { collecting = true; continue; }
            if (!collecting) continue;

            // Collect working-set blocks from the patch
            Optional<Patch> patch = findPatch(rev.getPatchId());
            patch.ifPresent(p -> {
                if (p.getWorkingSet() != null) modified.addAll(p.getWorkingSet());
            });

            if (rev.getRevisionId().equals(untilRevisionId)) break;
        }
        return modified;
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private String snapshotKey(String sessionId, String revisionId) {
        return sessionId + "::" + (revisionId == null || revisionId.isBlank() ? "initial" : revisionId);
    }

    private DocumentComponent cloneTree(DocumentComponent root) {
        return objectMapper.convertValue(root, DocumentComponent.class);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise revision payload", e);
        }
    }
}
