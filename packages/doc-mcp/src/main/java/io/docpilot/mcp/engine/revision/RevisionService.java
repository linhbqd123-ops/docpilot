package io.docpilot.mcp.engine.revision;

import io.docpilot.mcp.engine.patch.PatchEngine;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.patch.PatchValidation;
import io.docpilot.mcp.model.revision.ConflictRevision;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.revision.RevisionStatus;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.personalization.SemanticSearchService;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Manages the revision lifecycle for a document session.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Stage a patch as a {@code PENDING} revision</li>
 *   <li>Apply (commit) a pending revision to the session</li>
 *   <li>Reject a pending revision</li>
 *   <li>Detect concurrent-edit conflicts using {@code baseRevisionId} comparison</li>
 *   <li>Attempt rebase when the working-set does NOT overlap with concurrent edits</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RevisionService {

    private final PatchEngine patchEngine;
    private final RevisionStore revisionStore;
    private final DocumentSessionStore sessionStore;
    private final SemanticSearchService semanticSearchService;

    // -----------------------------------------------------------------------
    //  Stage (create PENDING revision)
    // -----------------------------------------------------------------------

    /**
     * Validates the patch and stages it as a {@code PENDING} revision ready for
     * user review.  Does not mutate the session.
     *
     * @return the staged revision
     */
    public Revision stage(Patch patch, DocumentSession session, String author) {
        PatchValidation validation = patchEngine.dryRun(patch, session);

        String revisionId = "rev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Revision revision = Revision.builder()
            .revisionId(revisionId)
            .sessionId(session.getSessionId())
            .baseRevisionId(patch.getBaseRevisionId())
            .patchId(patch.getPatchId())
            .status(validation.getErrors().isEmpty() ? RevisionStatus.PENDING : RevisionStatus.REJECTED)
            .createdAt(Instant.now())
            .summary(patch.getSummary())
            .author(author)
            .scope(validation.getScope())
            .build();

        revisionStore.saveRevision(revision);
        revisionStore.savePatch(patch.getPatchId(), patch);
        log.info("Staged revision {} for session {} (status={})", revisionId, session.getSessionId(), revision.getStatus());
        return revision;
    }

    // -----------------------------------------------------------------------
    //  Apply
    // -----------------------------------------------------------------------

    /**
     * Applies a pending revision to the session.
     *
     * <p>Before applying, checks for optimistic-concurrency conflicts:
     * <ul>
     *   <li>If {@code baseRevisionId} matches the current session revision → apply directly.</li>
     *   <li>If there are newer manual revisions but their working sets don't overlap → rebase and apply.</li>
     *   <li>If working sets overlap → create a {@link ConflictRevision} instead.</li>
     * </ul>
     */
    public Revision apply(String revisionId, DocumentSession session) {
        Revision revision = revisionStore.findRevision(revisionId)
            .orElseThrow(() -> new NoSuchElementException("Revision not found: " + revisionId));

        if (revision.getStatus() == RevisionStatus.APPLIED) {
            log.warn("Revision {} already applied", revisionId);
            return revision;
        }
        if (revision.getStatus() == RevisionStatus.REJECTED) {
            throw new IllegalStateException("Cannot apply a REJECTED revision: " + revisionId);
        }

        Patch patch = revisionStore.findPatch(revision.getPatchId())
            .orElseThrow(() -> new NoSuchElementException("Patch not found: " + revision.getPatchId()));

        // ── Concurrency check ─────────────────────────────────────────────
        String currentRev = session.getCurrentRevisionId();
        if (!Objects.equals(revision.getBaseRevisionId(), currentRev)) {
            // Newer revisions have been applied — check for overlap
            List<String> agentWorkingSet   = patch.getWorkingSet() != null ? patch.getWorkingSet() : List.of();
            List<String> concurrentBlocks  = revisionStore.collectModifiedBlocks(
                session.getSessionId(), revision.getBaseRevisionId(), currentRev);

            List<String> overlap = agentWorkingSet.stream()
                .filter(concurrentBlocks::contains)
                .toList();

            if (!overlap.isEmpty()) {
                log.warn("Conflict detected for revision {} — overlapping blocks: {}", revisionId, overlap);
                ConflictRevision conflict = ConflictRevision.builder()
                    .conflictId("conflict_" + UUID.randomUUID())
                    .sessionId(session.getSessionId())
                    .agentPatchId(patch.getPatchId())
                    .manualRevisionId(currentRev)
                    .conflictingBlockIds(overlap)
                    .agentChangeSummary(patch.getSummary())
                    .build();
                revisionStore.saveConflict(conflict);

                Revision conflictRevision = updateStatus(revision, RevisionStatus.CONFLICT);
                revisionStore.saveRevision(conflictRevision);
                return conflictRevision;
            }
            log.info("Revision {} rebased onto {} (no overlap)", revisionId, currentRev);
        }

        // ── Apply ─────────────────────────────────────────────────────────
        patchEngine.apply(patch, session, revisionId, revision.getAuthor());
        revisionStore.saveSnapshot(session.getSessionId(), revisionId, session.getRoot());
        sessionStore.save(session);
        semanticSearchService.reindexSession(session);

        Revision applied = updateStatus(revision, RevisionStatus.APPLIED);
        applied = applied.toBuilder().appliedAt(Instant.now()).build();
        revisionStore.saveRevision(applied);
        log.info("Revision {} applied to session {}", revisionId, session.getSessionId());
        return applied;
    }

    // -----------------------------------------------------------------------
    //  Reject
    // -----------------------------------------------------------------------

    public Revision reject(String revisionId) {
        Revision revision = revisionStore.findRevision(revisionId)
            .orElseThrow(() -> new NoSuchElementException("Revision not found: " + revisionId));
        Revision rejected = updateStatus(revision, RevisionStatus.REJECTED);
        revisionStore.saveRevision(rejected);
        log.info("Revision {} rejected", revisionId);
        return rejected;
    }

    // -----------------------------------------------------------------------
    //  Rollback
    // -----------------------------------------------------------------------

    /**
     * Rolls back the session to the state just before {@code revisionId} was applied.
     *
     * <p>This is implemented by re-importing the last known DOCX snapshot (if available).
     * A simplified in-memory rollback re-traverses the revision chain; full snapshot
     * rollback is handled by the session store's snapshot mechanism.
     */
    public Revision rollback(String revisionId, DocumentSession session) {
        Revision revision = revisionStore.findRevision(revisionId)
            .orElseThrow(() -> new NoSuchElementException("Revision not found: " + revisionId));

        if (revision.getStatus() != RevisionStatus.APPLIED) {
            throw new IllegalStateException("Can only roll back an APPLIED revision");
        }

        if (!Objects.equals(session.getCurrentRevisionId(), revisionId)) {
            throw new IllegalStateException("Can only roll back the current applied revision");
        }

        DocumentComponent restoredRoot = revision.getBaseRevisionId() == null || revision.getBaseRevisionId().isBlank()
            ? revisionStore.findInitialSnapshot(session.getSessionId())
                .orElseThrow(() -> new IllegalStateException("Initial snapshot not found for session " + session.getSessionId()))
            : revisionStore.findSnapshot(session.getSessionId(), revision.getBaseRevisionId())
                .orElseThrow(() -> new IllegalStateException("Snapshot not found for revision " + revision.getBaseRevisionId()));

        session.setRoot(restoredRoot);
        session.setCurrentRevisionId(revision.getBaseRevisionId());
        session.setLastModifiedAt(Instant.now());
        sessionStore.save(session);
        semanticSearchService.reindexSession(session);

        Revision rolledBack = updateStatus(revision, RevisionStatus.REJECTED);
        revisionStore.saveRevision(rolledBack);
        log.info("Revision {} rolled back for session {}", revisionId, session.getSessionId());
        return rolledBack;
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private Revision updateStatus(Revision revision, RevisionStatus newStatus) {
        return Revision.builder()
            .revisionId(revision.getRevisionId())
            .sessionId(revision.getSessionId())
            .baseRevisionId(revision.getBaseRevisionId())
            .patchId(revision.getPatchId())
            .status(newStatus)
            .createdAt(revision.getCreatedAt())
            .appliedAt(revision.getAppliedAt())
            .summary(revision.getSummary())
            .author(revision.getAuthor())
            .scope(revision.getScope())
            .build();
    }
}
