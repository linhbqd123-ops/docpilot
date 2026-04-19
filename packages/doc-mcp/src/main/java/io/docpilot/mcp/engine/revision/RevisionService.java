package io.docpilot.mcp.engine.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.converter.AnalysisHtmlConverter;
import io.docpilot.mcp.engine.diff.DiffService;
import io.docpilot.mcp.engine.fidelity.FidelityHtmlService;
import io.docpilot.mcp.engine.patch.PatchEngine;
import io.docpilot.mcp.exception.ConflictException;
import io.docpilot.mcp.model.diff.DocumentDiff;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.patch.PatchValidation;
import io.docpilot.mcp.model.revision.ConflictRevision;
import io.docpilot.mcp.model.revision.PendingRevisionPreview;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.revision.RevisionStatus;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.personalization.SemanticSearchService;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
public class RevisionService {

    private final PatchEngine patchEngine;
    private final DiffService diffService;
    private final RevisionStore revisionStore;
    private final DocumentSessionStore sessionStore;
    private final SemanticSearchService semanticSearchService;
    private final FidelityHtmlService fidelityHtmlService;
    private final AnalysisHtmlConverter analysisHtmlConverter;
    private final ObjectMapper objectMapper;

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

        revisionStore.savePatch(patch.getPatchId(), patch);
        revisionStore.saveRevision(revision);
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
            throw new ConflictException("This revision has already been rejected and cannot be applied.");
        }
        if (revision.getStatus() == RevisionStatus.CONFLICT) {
            throw new ConflictException("This revision is in conflict and cannot be applied.");
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

        PatchValidation applyValidation = patchEngine.dryRun(patch, session);
        if (!applyValidation.getErrors().isEmpty()) {
            Revision rejected = updateStatus(revision, RevisionStatus.REJECTED);
            revisionStore.saveRevision(rejected);
            throw new ConflictException(
                "This revision can no longer be applied: " + applyValidation.getErrors().get(0)
            );
        }

        String sourceHtml = sessionStore.findTextAsset(session.getSessionId(), FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME)
            .orElseThrow(() -> new IllegalStateException(
                "Current fidelity source HTML is missing for session " + session.getSessionId() + "."
            ));

        // ── Apply ─────────────────────────────────────────────────────────
        try {
            patchEngine.apply(patch, session, revisionId, revision.getAuthor());
        } catch (IllegalStateException patchErr) {
            log.error("Failed to apply patch during revision apply: {}", patchErr.getMessage(), patchErr);
            // Reject the revision and provide user feedback
            Revision rejected = updateStatus(revision, RevisionStatus.REJECTED);
            revisionStore.saveRevision(rejected);
            throw new ConflictException(
                "The patch cannot be applied to the current document state. This may happen if the document was modified " +
                "after the patch was staged. Details: " + patchErr.getMessage()
            );
        }
        
        syncFidelityHtmlAfterApply(session, patch, revisionId, sourceHtml);
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
    //  Apply Partial
    // -----------------------------------------------------------------------

    /**
     * Applies only the operations at {@code acceptedIndices} from a pending revision.
     *
     * <ul>
     *   <li>Empty indices → revision is rejected without applying anything.</li>
     *   <li>All indices → delegates to {@link #apply} (standard path).</li>
     *   <li>Partial → creates a filtered patch, applies it as a new APPLIED revision,
     *       and rejects the original revision.</li>
     * </ul>
     *
     * @param revisionId      the PENDING revision to partially apply
     * @param acceptedIndices 0-based indices of operations from the original patch to accept
     * @param session         the live document session
     * @return the resulting revision (new APPLIED or REJECTED original)
     */
    public Revision applyPartial(String revisionId, List<Integer> acceptedIndices, DocumentSession session) {
        Revision revision = revisionStore.findRevision(revisionId)
            .orElseThrow(() -> new NoSuchElementException("Revision not found: " + revisionId));

        if (revision.getStatus() != RevisionStatus.PENDING) {
            throw new ConflictException("Only a PENDING revision can be partially applied.");
        }

        Patch originalPatch = revisionStore.findPatch(revision.getPatchId())
            .orElseThrow(() -> new NoSuchElementException("Patch not found: " + revision.getPatchId()));

        List<io.docpilot.mcp.model.patch.PatchOperation> allOps = originalPatch.getOperations() != null
            ? originalPatch.getOperations()
            : List.of();

        List<Integer> sortedIndices = acceptedIndices == null ? List.of() : acceptedIndices.stream()
            .filter(i -> i >= 0 && i < allOps.size())
            .distinct()
            .sorted()
            .toList();

        if (sortedIndices.isEmpty()) {
            return reject(revisionId);
        }

        if (sortedIndices.size() == allOps.size()) {
            return apply(revisionId, session);
        }

        List<io.docpilot.mcp.model.patch.PatchOperation> selectedOps = sortedIndices.stream()
            .map(allOps::get)
            .toList();

        List<String> partialWorkingSet = selectedOps.stream()
            .map(io.docpilot.mcp.model.patch.PatchOperation::getTarget)
            .filter(t -> t != null && t.getBlockId() != null && !t.getBlockId().isBlank())
            .map(io.docpilot.mcp.model.patch.PatchTarget::getBlockId)
            .distinct()
            .toList();

        String partialPatchId = "patch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Patch partialPatch = Patch.builder()
            .patchId(partialPatchId)
            .sessionId(originalPatch.getSessionId())
            .baseRevisionId(originalPatch.getBaseRevisionId())
            .operations(selectedOps)
            .summary(originalPatch.getSummary() + " (partial)")
            .author(originalPatch.getAuthor())
            .workingSet(partialWorkingSet)
            .createdAt(Instant.now())
            .build();

        String partialRevisionId = "rev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String sourceHtml = sessionStore.findTextAsset(session.getSessionId(), FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME)
            .orElseThrow(() -> new IllegalStateException(
                "Current fidelity source HTML is missing for session " + session.getSessionId() + "."
            ));

        try {
            patchEngine.apply(partialPatch, session, partialRevisionId, revision.getAuthor());
        } catch (IllegalStateException patchErr) {
            log.error("Failed to apply partial patch: {}", patchErr.getMessage(), patchErr);
            throw new ConflictException(
                "The partial patch cannot be applied to the current document state. Details: " + patchErr.getMessage()
            );
        }

        syncFidelityHtmlAfterApply(session, partialPatch, partialRevisionId, sourceHtml);
        revisionStore.saveSnapshot(session.getSessionId(), partialRevisionId, session.getRoot());
        sessionStore.save(session);
        semanticSearchService.reindexSession(session);

        revisionStore.savePatch(partialPatchId, partialPatch);

        Revision partialRevision = Revision.builder()
            .revisionId(partialRevisionId)
            .sessionId(session.getSessionId())
            .baseRevisionId(revision.getBaseRevisionId())
            .patchId(partialPatchId)
            .status(RevisionStatus.APPLIED)
            .createdAt(Instant.now())
            .appliedAt(Instant.now())
            .summary(partialPatch.getSummary())
            .author(revision.getAuthor())
            .scope(revision.getScope())
            .build();
        revisionStore.saveRevision(partialRevision);

        Revision rejected = updateStatus(revision, RevisionStatus.REJECTED);
        revisionStore.saveRevision(rejected);

        log.info("Partially applied revision {} ({}/{} operations) as new revision {} for session {}",
            revisionId, sortedIndices.size(), allOps.size(), partialRevisionId, session.getSessionId());
        return partialRevision;
    }

    // -----------------------------------------------------------------------
    //  Reject
    // -----------------------------------------------------------------------

    public Revision reject(String revisionId) {
        Revision revision = revisionStore.findRevision(revisionId)
            .orElseThrow(() -> new NoSuchElementException("Revision not found: " + revisionId));

        if (revision.getStatus() == RevisionStatus.APPLIED) {
            throw new ConflictException("An applied revision cannot be rejected. Roll it back instead.");
        }
        if (revision.getStatus() == RevisionStatus.REJECTED) {
            return revision;
        }

        Revision rejected = updateStatus(revision, RevisionStatus.REJECTED);
        revisionStore.saveRevision(rejected);
        log.info("Revision {} rejected", revisionId);
        return rejected;
    }

    public PendingRevisionPreview preview(String revisionId, DocumentSession session) {
        Revision revision = revisionStore.findRevision(revisionId)
            .orElseThrow(() -> new NoSuchElementException("Revision not found: " + revisionId));

        if (revision.getStatus() != RevisionStatus.PENDING) {
            throw new IllegalArgumentException("Only pending revisions can be previewed.");
        }

        Patch patch = revisionStore.findPatch(revision.getPatchId())
            .orElseThrow(() -> new NoSuchElementException("Patch not found: " + revision.getPatchId()));

        PatchValidation validation = patchEngine.dryRun(patch, session);
        PendingRevisionPreview.PendingRevisionPreviewBuilder preview = PendingRevisionPreview.builder()
            .revisionId(revision.getRevisionId())
            .sessionId(session.getSessionId())
            .baseRevisionId(revision.getBaseRevisionId())
            .currentRevisionId(session.getCurrentRevisionId())
            .available(validation.getErrors().isEmpty())
            .validation(validation);

        if (!validation.getErrors().isEmpty()) {
            return preview
                .message(validation.getErrors().get(0))
                .build();
        }

        DocumentSession previewSession = cloneSession(session);

        try {
            patchEngine.apply(patch, previewSession, revisionId, revision.getAuthor());
        } catch (IllegalStateException ex) {
            return preview
                .available(false)
                .message(ex.getMessage())
                .build();
        }

        DocumentDiff diff = diffService.compute(
            session.getRoot(),
            previewSession.getRoot(),
            session.getCurrentRevisionId(),
            revisionId,
            session.getSessionId()
        );
        preview.diff(diff);

        sessionStore.findTextAsset(session.getSessionId(), FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME)
            .ifPresent(sourceHtml -> {
                String previewHtml = fidelityHtmlService.applyPatch(sourceHtml, patch, previewSession);
                preview.html(previewHtml);
                preview.sourceHtml(previewHtml);
            });

        return preview.build();
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
            throw new ConflictException("Only an applied revision can be rolled back.");
        }

        if (!Objects.equals(session.getCurrentRevisionId(), revisionId)) {
            throw new ConflictException("Only the current applied revision can be rolled back.");
        }

        String restoredHtml = loadFidelityHtmlSnapshot(session.getSessionId(), revision.getBaseRevisionId());

        DocumentComponent restoredRoot = revision.getBaseRevisionId() == null || revision.getBaseRevisionId().isBlank()
            ? revisionStore.findInitialSnapshot(session.getSessionId())
                .orElseThrow(() -> new IllegalStateException("Initial snapshot not found for session " + session.getSessionId()))
            : revisionStore.findSnapshot(session.getSessionId(), revision.getBaseRevisionId())
                .orElseThrow(() -> new IllegalStateException("Snapshot not found for revision " + revision.getBaseRevisionId()));

        session.setRoot(restoredRoot);
        session.setCurrentRevisionId(revision.getBaseRevisionId());
        session.setLastModifiedAt(Instant.now());
    restoreFidelityHtmlSnapshot(session, restoredHtml);
        sessionStore.save(session);
        semanticSearchService.reindexSession(session);

        Revision rolledBack = updateStatus(revision, RevisionStatus.REJECTED);
        revisionStore.saveRevision(rolledBack);
        log.info("Revision {} rolled back for session {}", revisionId, session.getSessionId());
        return rolledBack;
    }

    public Revision restore(String revisionId, DocumentSession session) {
        Revision revision = revisionStore.findRevision(revisionId)
            .orElseThrow(() -> new NoSuchElementException("Revision not found: " + revisionId));

        if (revision.getAppliedAt() == null && revision.getStatus() != RevisionStatus.APPLIED) {
            throw new ConflictException("Only an applied checkpoint can be restored.");
        }

        if (Objects.equals(session.getCurrentRevisionId(), revisionId)) {
            return revision;
        }

        String restoredHtml = loadFidelityHtmlSnapshot(session.getSessionId(), revisionId);

        DocumentComponent restoredRoot = revisionStore.findSnapshot(session.getSessionId(), revisionId)
            .orElseThrow(() -> new IllegalStateException(
                "Snapshot not found for revision " + revisionId));

        session.setRoot(restoredRoot);
        session.setCurrentRevisionId(revisionId);
        session.setLastModifiedAt(Instant.now());
        restoreFidelityHtmlSnapshot(session, restoredHtml);
        sessionStore.save(session);
        semanticSearchService.reindexSession(session);

        Revision restored = revision;
        if (revision.getStatus() == RevisionStatus.REJECTED && revision.getAppliedAt() != null) {
            restored = updateStatus(revision, RevisionStatus.APPLIED)
                .toBuilder()
                .appliedAt(revision.getAppliedAt())
                .build();
            revisionStore.saveRevision(restored);
        }

        log.info("Revision {} restored for session {}", revisionId, session.getSessionId());
        return restored;
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

    private void syncFidelityHtmlAfterApply(DocumentSession session, Patch patch, String revisionId, String sourceHtml) {
        String updatedSourceHtml = fidelityHtmlService.applyPatch(sourceHtml, patch, session);
        sessionStore.saveTextAsset(session.getSessionId(), FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME, updatedSourceHtml);
        sessionStore.saveTextAsset(session.getSessionId(), FidelityHtmlService.CURRENT_ANALYSIS_ASSET_NAME, analysisHtmlConverter.convert(updatedSourceHtml));
        sessionStore.saveTextAsset(session.getSessionId(), FidelityHtmlService.revisionSourceSnapshotAssetName(revisionId), updatedSourceHtml);
    }

    private String loadFidelityHtmlSnapshot(String sessionId, String revisionId) {
        String assetName = revisionId == null || revisionId.isBlank()
            ? FidelityHtmlService.ORIGINAL_SOURCE_ASSET_NAME
            : FidelityHtmlService.revisionSourceSnapshotAssetName(revisionId);

        return sessionStore.findTextAsset(sessionId, assetName)
            .orElseThrow(() -> new IllegalStateException(
                "Fidelity source snapshot '" + assetName + "' is missing for session " + sessionId + "."
            ));
    }

    private void restoreFidelityHtmlSnapshot(DocumentSession session, String restoredHtml) {
        String aligned = fidelityHtmlService.annotateForSession(restoredHtml, session);
        sessionStore.saveTextAsset(session.getSessionId(), FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME, aligned);
        sessionStore.saveTextAsset(session.getSessionId(), FidelityHtmlService.CURRENT_ANALYSIS_ASSET_NAME, analysisHtmlConverter.convert(aligned));
    }

    private DocumentSession cloneSession(DocumentSession session) {
        return objectMapper.convertValue(session, DocumentSession.class);
    }
}
