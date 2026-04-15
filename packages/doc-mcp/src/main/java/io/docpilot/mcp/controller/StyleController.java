package io.docpilot.mcp.controller;

import io.docpilot.mcp.model.legacy.DocumentStructure;
import io.docpilot.mcp.model.legacy.StyleRegistry;
import io.docpilot.mcp.service.ConversionService;
import io.docpilot.mcp.service.RegistryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for style registry and document structure (ported from doc-processor).
 *
 * GET    /api/styles/{docId}     — retrieve stored StyleRegistry
 * DELETE /api/styles/{docId}     — evict StyleRegistry
 * POST   /api/structure          — extract document outline from DOCX
 * GET    /api/structure/{docId}  — retrieve previously extracted structure
 */
@RestController
@RequiredArgsConstructor
public class StyleController {

    private final RegistryQueryService queryService;
    private final ConversionService conversionService;

    @GetMapping(value = "/api/styles/{docId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StyleRegistry> getStyles(@PathVariable String docId) {
        return ResponseEntity.ok(queryService.getRegistry(docId));
    }

    @DeleteMapping("/api/styles/{docId}")
    public ResponseEntity<Void> deleteStyles(@PathVariable String docId) {
        queryService.deleteRegistry(docId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/api/structure", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentStructure> extractStructure(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(conversionService.extractStructure(file));
    }

    @GetMapping(value = "/api/structure/{docId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentStructure> getStructure(@PathVariable String docId) {
        return ResponseEntity.ok(queryService.getStructure(docId));
    }
}
