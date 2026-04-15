package io.docpilot.mcp.controller;

import io.docpilot.mcp.engine.importer.DocxImportService;
import io.docpilot.mcp.engine.projection.HtmlProjectionService;
import io.docpilot.mcp.exception.NotFoundException;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
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
            sessionStore.save(session);
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
        return ResponseEntity.ok(Map.of(
            "session_id", s.getSessionId(),
            "doc_id", s.getDocId(),
            "filename", s.getFilename(),
            "state", s.getState().name(),
            "current_revision_id", s.getCurrentRevisionId() != null ? s.getCurrentRevisionId() : "",
            "word_count", s.getWordCount(),
            "paragraph_count", s.getParagraphCount(),
            "table_count", s.getTableCount(),
            "image_count", s.getImageCount(),
            "section_count", s.getSectionCount(),
            "created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : ""
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
}
