package io.docpilot.processor.controller;

import io.docpilot.processor.model.DocumentStructure;
import io.docpilot.processor.model.StyleRegistry;
import io.docpilot.processor.service.ConversionService;
import io.docpilot.processor.service.RegistryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for style registry queries and document structure extraction.
 *
 * <pre>
 *   GET    /api/styles/{docId}       Retrieve stored StyleRegistry
 *   DELETE /api/styles/{docId}       Evict StyleRegistry
 *   POST   /api/structure            Extract document outline from a DOCX file
 *   GET    /api/structure/{docId}    Retrieve previously extracted structure
 * </pre>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Styles & Structure", description = "Style registry retrieval and document outline extraction")
public class StyleController {

    private final RegistryQueryService queryService;
    private final ConversionService conversionService;

    // -----------------------------------------------------------------------
    //  Style registry
    // -----------------------------------------------------------------------

    @GetMapping(value = "/api/styles/{docId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Get style registry",
        description = "Returns the StyleRegistry extracted during a prior docx-to-html conversion."
    )
    public ResponseEntity<StyleRegistry> getStyles(
        @Parameter(description = "Document ID returned by /api/convert/docx-to-html")
        @PathVariable String docId
    ) {
        return ResponseEntity.ok(queryService.getRegistry(docId));
    }

    @DeleteMapping("/api/styles/{docId}")
    @Operation(summary = "Delete style registry", description = "Evicts both the in-memory cache and disk file for this docId.")
    public ResponseEntity<Void> deleteStyles(@PathVariable String docId) {
        queryService.deleteRegistry(docId);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    //  Document structure
    // -----------------------------------------------------------------------

    @PostMapping(
        value = "/api/structure",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Extract document structure",
        description = """
            Analyses a DOCX file and returns a hierarchical outline together with
            document statistics (page count estimate, word count, table count, etc.).
            The result is persisted and retrievable via GET /api/structure/{docId}.
            """
    )
    public ResponseEntity<DocumentStructure> extractStructure(
        @RequestParam("file") MultipartFile file
    ) {
        DocumentStructure structure = conversionService.extractStructure(file);
        return ResponseEntity.ok(structure);
    }

    @GetMapping(value = "/api/structure/{docId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Get previously extracted structure",
        description = "Returns the DocumentStructure extracted during a prior /api/structure call."
    )
    public ResponseEntity<DocumentStructure> getStructure(@PathVariable String docId) {
        return ResponseEntity.ok(queryService.getStructure(docId));
    }
}
