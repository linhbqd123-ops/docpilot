package io.docpilot.mcp.controller;

import io.docpilot.mcp.engine.revision.RevisionService;
import io.docpilot.mcp.exception.NotFoundException;
import io.docpilot.mcp.model.revision.PendingRevisionPreview;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for revision operations.
 *
 * GET  /api/revisions/{id}          — get revision details
 * POST /api/revisions/{id}/apply    — apply PENDING revision
 * POST /api/revisions/{id}/reject   — reject revision
 * POST /api/revisions/{id}/rollback — rollback an applied revision
 * POST /api/revisions/{id}/restore  — restore the session to an applied checkpoint
 */
@RestController
@RequestMapping("/api/revisions")
@RequiredArgsConstructor
public class RevisionController {

    private final RevisionStore revisionStore;
    private final RevisionService revisionService;
    private final DocumentSessionStore sessionStore;

    @GetMapping("/{id}")
    public ResponseEntity<Revision> getRevision(@PathVariable String id) {
        Revision r = revisionStore.findRevision(id)
            .orElseThrow(() -> new NotFoundException("Revision not found: " + id));
        return ResponseEntity.ok(r);
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<PendingRevisionPreview> previewRevision(@PathVariable String id) {
        Revision r = revisionStore.findRevision(id)
            .orElseThrow(() -> new NotFoundException("Revision not found: " + id));
        DocumentSession s = sessionStore.find(r.getSessionId())
            .orElseThrow(() -> new NotFoundException("Session not found: " + r.getSessionId()));
        return ResponseEntity.ok(revisionService.preview(id, s));
    }

    @PostMapping("/{id}/apply")
    public ResponseEntity<Map<String, Object>> applyRevision(@PathVariable String id) {
        Revision r = revisionStore.findRevision(id)
            .orElseThrow(() -> new NotFoundException("Revision not found: " + id));
        DocumentSession s = sessionStore.find(r.getSessionId())
            .orElseThrow(() -> new NotFoundException("Session not found: " + r.getSessionId()));
        Revision applied = revisionService.apply(id, s);
        return ResponseEntity.ok(Map.of(
            "revision_id", applied.getRevisionId(),
            "status", applied.getStatus().name(),
            "applied_at", applied.getAppliedAt() != null ? applied.getAppliedAt().toString() : "",
            "current_revision_id", s.getCurrentRevisionId() != null ? s.getCurrentRevisionId() : ""
        ));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectRevision(@PathVariable String id) {
        revisionStore.findRevision(id)
            .orElseThrow(() -> new NotFoundException("Revision not found: " + id));
        Revision rejected = revisionService.reject(id);
        return ResponseEntity.ok(Map.of(
            "revision_id", rejected.getRevisionId(),
            "status", rejected.getStatus().name()
        ));
    }

    @PostMapping("/{id}/rollback")
    public ResponseEntity<Map<String, Object>> rollbackRevision(@PathVariable String id) {
        Revision r = revisionStore.findRevision(id)
            .orElseThrow(() -> new NotFoundException("Revision not found: " + id));
        DocumentSession s = sessionStore.find(r.getSessionId())
            .orElseThrow(() -> new NotFoundException("Session not found: " + r.getSessionId()));
        Revision rolled = revisionService.rollback(id, s);
        return ResponseEntity.ok(Map.of(
            "revision_id", rolled.getRevisionId(),
            "status", rolled.getStatus().name(),
            "current_revision_id", s.getCurrentRevisionId() != null ? s.getCurrentRevisionId() : ""
        ));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Map<String, Object>> restoreRevision(@PathVariable String id) {
        Revision r = revisionStore.findRevision(id)
            .orElseThrow(() -> new NotFoundException("Revision not found: " + id));
        DocumentSession s = sessionStore.find(r.getSessionId())
            .orElseThrow(() -> new NotFoundException("Session not found: " + r.getSessionId()));
        Revision restored = revisionService.restore(id, s);
        return ResponseEntity.ok(Map.of(
            "revision_id", restored.getRevisionId(),
            "status", restored.getStatus().name(),
            "current_revision_id", s.getCurrentRevisionId() != null ? s.getCurrentRevisionId() : ""
        ));
    }

    record PartialApplyRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("accepted_indices") List<Integer> acceptedIndices
    ) {}

    @PostMapping("/{id}/apply-partial")
    public ResponseEntity<Map<String, Object>> applyPartialRevision(
            @PathVariable String id,
            @RequestBody PartialApplyRequest body) {
        Revision r = revisionStore.findRevision(id)
            .orElseThrow(() -> new NotFoundException("Revision not found: " + id));
        DocumentSession s = sessionStore.find(r.getSessionId())
            .orElseThrow(() -> new NotFoundException("Session not found: " + r.getSessionId()));
        List<Integer> indices = body.acceptedIndices() != null ? body.acceptedIndices() : List.of();
        Revision result = revisionService.applyPartial(id, indices, s);
        return ResponseEntity.ok(Map.of(
            "revision_id", result.getRevisionId(),
            "status", result.getStatus().name(),
            "applied_at", result.getAppliedAt() != null ? result.getAppliedAt().toString() : "",
            "current_revision_id", s.getCurrentRevisionId() != null ? s.getCurrentRevisionId() : ""
        ));
    }
}
