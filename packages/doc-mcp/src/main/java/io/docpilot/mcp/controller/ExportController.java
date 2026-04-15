package io.docpilot.mcp.controller;

import io.docpilot.mcp.engine.exporter.DocxExportService;
import io.docpilot.mcp.exception.NotFoundException;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * REST controller for DOCX export.
 *
 * POST /api/sessions/{id}/export-docx — export current session state as DOCX
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class ExportController {

    private final DocumentSessionStore sessionStore;
    private final DocxExportService exportService;

    @PostMapping(value = "/{id}/export-docx",
                 produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    public ResponseEntity<byte[]> exportDocx(@PathVariable String id) throws Exception {
        DocumentSession s = sessionStore.find(id)
            .orElseThrow(() -> new NotFoundException("Session not found: " + id));

        byte[] docxBytes = exportService.export(s);

        String filename = s.getFilename() != null
            ? s.getFilename().replace(".docx", "_edited.docx")
            : "document_edited.docx";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
            ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build());
        headers.setContentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

        return ResponseEntity.ok().headers(headers).body(docxBytes);
    }
}
