package io.docpilot.mcp.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.revision.ConflictRevision;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.revision.RevisionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store for {@link Revision}, {@link Patch}, and {@link ConflictRevision} objects.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class RevisionStore {

    private final AppProperties props;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Revision>         revisionCache  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Patch>            patchCache     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConflictRevision> conflictCache  = new ConcurrentHashMap<>();

    // ── Revisions ──────────────────────────────────────────────────────────

    public void saveRevision(Revision revision) {
        revisionCache.put(revision.getRevisionId(), revision);
        persist(revPath(revision.getRevisionId()), revision);
    }

    public Optional<Revision> findRevision(String revisionId) {
        Revision cached = revisionCache.get(revisionId);
        if (cached != null) return Optional.of(cached);
        Path file = revPath(revisionId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            Revision loaded = objectMapper.readValue(file.toFile(), Revision.class);
            revisionCache.put(revisionId, loaded);
            return Optional.of(loaded);
        } catch (IOException e) {
            log.warn("Cannot deserialise revision {}: {}", revisionId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Revision> findBySession(String sessionId) {
        return revisionCache.values().stream()
            .filter(r -> sessionId.equals(r.getSessionId()))
            .sorted(Comparator.comparing(Revision::getCreatedAt).reversed())
            .toList();
    }

    // ── Patches ─────────────────────────────────────────────────────────────

    public void savePatch(String patchId, Patch patch) {
        patchCache.put(patchId, patch);
        persist(patchPath(patchId), patch);
    }

    public Optional<Patch> findPatch(String patchId) {
        Patch cached = patchCache.get(patchId);
        if (cached != null) return Optional.of(cached);
        Path file = patchPath(patchId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            Patch loaded = objectMapper.readValue(file.toFile(), Patch.class);
            patchCache.put(patchId, loaded);
            return Optional.of(loaded);
        } catch (IOException e) {
            log.warn("Cannot deserialise patch {}: {}", patchId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Conflicts ───────────────────────────────────────────────────────────

    public void saveConflict(ConflictRevision conflict) {
        conflictCache.put(conflict.getConflictId(), conflict);
        persist(conflictPath(conflict.getConflictId()), conflict);
    }

    public Optional<ConflictRevision> findConflict(String conflictId) {
        return Optional.ofNullable(conflictCache.get(conflictId));
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
        boolean collecting    = false;

        // Walk revision chain in chronological order
        List<Revision> sessionRevisions = findBySession(sessionId).stream()
            .filter(r -> r.getStatus() == RevisionStatus.APPLIED)
            .sorted(Comparator.comparing(Revision::getAppliedAt))
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

    private Path revPath(String id) {
        return props.storage().revisionsPath().resolve(id + ".revision.json");
    }

    private Path patchPath(String id) {
        return props.storage().revisionsPath().resolve(id + ".patch.json");
    }

    private Path conflictPath(String id) {
        return props.storage().revisionsPath().resolve(id + ".conflict.json");
    }

    private void persist(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(path.toFile(), value);
        } catch (IOException e) {
            log.error("Failed to persist to {}: {}", path, e.getMessage());
        }
    }
}
