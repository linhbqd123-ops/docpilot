package io.docpilot.mcp.controller;

import io.docpilot.mcp.engine.importer.DocxImportService;
import io.docpilot.mcp.engine.projection.HtmlProjectionService;
import io.docpilot.mcp.exception.NotFoundException;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.personalization.SemanticSearchService;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for document session lifecycle.
 *
 * POST   /api/sessions/import-docx          — upload DOCX, create session
 * GET    /api/sessions/{id}                  — full session (metadata + tree)
 * GET    /api/sessions/{id}/summary          — lightweight metadata
 * GET    /api/sessions/{id}/projection/html  — annotated HTML projection
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Slf4j
public class SessionController {

    private final DocxImportService importService;
    private final HtmlProjectionService htmlProjectionService;
    private final DocumentSessionStore sessionStore;
    private final RevisionStore revisionStore;
    private final SemanticSearchService semanticSearchService;

    // ─── import ───────────────────────────────────────────────────────────────

    @PostMapping(value = "/import-docx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importDocx(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".docx")) {
            throw new IllegalArgumentException("Only .docx files are supported.");
        }

        String sessionId = UUID.randomUUID().toString();
        String docId     = UUID.randomUUID().toString();

        try (InputStream in = file.getInputStream()) {
            DocumentSession session = importService.importDocx(in, sessionId, docId, originalName);
            semanticSearchService.reindexSession(session);
            sessionStore.save(session);
            revisionStore.saveInitialSnapshot(sessionId, session.getRoot());
            // Save raw DOCX bytes for rollback support
            sessionStore.saveDocxSnapshot(sessionId, file.getBytes());
            log.info("Imported DOCX: filename={} sessionId={} wordCount={}", originalName, sessionId, session.getWordCount());

            return ResponseEntity.ok(Map.of(
                "session_id", sessionId,
                "doc_id",     docId,
                "filename",   originalName,
                "word_count", session.getWordCount(),
                "paragraph_count", session.getParagraphCount(),
                "table_count", session.getTableCount(),
                "image_count", session.getImageCount(),
                "section_count", session.getSectionCount()
            ));
        }
    }

    // ─── get session ──────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<DocumentSession> getSession(@PathVariable String id) {
        DocumentSession s = sessionStore.find(id)
            .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        return ResponseEntity.ok(s);
    }

    // ─── summary ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}/summary")
    public ResponseEntity<Map<String, Object>> getSummary(@PathVariable String id) {
        DocumentSession s = sessionStore.find(id)
            .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        return ResponseEntity.ok(Map.ofEntries(
            Map.entry("session_id", s.getSessionId()),
            Map.entry("doc_id", s.getDocId()),
            Map.entry("filename", s.getFilename()),
            Map.entry("state", s.getState().name()),
            Map.entry("current_revision_id", s.getCurrentRevisionId() != null ? s.getCurrentRevisionId() : ""),
            Map.entry("word_count", s.getWordCount()),
            Map.entry("paragraph_count", s.getParagraphCount()),
            Map.entry("table_count", s.getTableCount()),
            Map.entry("image_count", s.getImageCount()),
            Map.entry("section_count", s.getSectionCount()),
            Map.entry("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "")
        ));
    }

    // ─── HTML projection ─────────────────────────────────────────────────────

    @GetMapping(value = "/{id}/projection/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getHtmlProjection(@PathVariable String id,
                                                     @RequestParam(defaultValue = "false") boolean fragment) {
        DocumentSession s = sessionStore.find(id)
            .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        String html = fragment ? htmlProjectionService.projectFragment(s) : htmlProjectionService.project(s);
        return ResponseEntity.ok(html);
    }

    @GetMapping("/{id}/revisions")
    public ResponseEntity<java.util.List<Map<String, Object>>> listRevisions(@PathVariable String id,
                                                                             @RequestParam(required = false) String status) {
        sessionStore.find(id)
            .orElseThrow(() -> new NotFoundException("Session not found: " + id));

        java.util.List<Map<String, Object>> revisions = revisionStore.findBySession(id).stream()
            .filter(revision -> status == null || status.isBlank() || revision.getStatus().name().equalsIgnoreCase(status))
            .map(this::toRevisionSummary)
            .toList();
        return ResponseEntity.ok(revisions);
    }

    private Map<String, Object> toRevisionSummary(Revision revision) {
        return Map.ofEntries(
            Map.entry("revision_id", revision.getRevisionId()),
            Map.entry("base_revision_id", revision.getBaseRevisionId() != null ? revision.getBaseRevisionId() : ""),
            Map.entry("status", revision.getStatus().name()),
            Map.entry("summary", revision.getSummary() != null ? revision.getSummary() : ""),
            Map.entry("author", revision.getAuthor() != null ? revision.getAuthor() : ""),
            Map.entry("scope", revision.getScope() != null ? revision.getScope() : ""),
            Map.entry("created_at", revision.getCreatedAt() != null ? revision.getCreatedAt().toString() : ""),
            Map.entry("applied_at", revision.getAppliedAt() != null ? revision.getAppliedAt().toString() : "")
        );
    }
}
