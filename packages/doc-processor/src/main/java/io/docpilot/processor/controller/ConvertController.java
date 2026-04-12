package io.docpilot.processor.controller;

import io.docpilot.processor.model.request.HtmlToDocxRequest;
import io.docpilot.processor.model.response.DocxToHtmlResponse;
import io.docpilot.processor.model.response.TextResponse;
import io.docpilot.processor.service.ConversionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller exposing all document <→ format conversion endpoints.
 *
 * <pre>
 *   POST /api/convert/docx-to-html       DOCX → HTML + StyleRegistry
 *   POST /api/convert/html-to-docx       HTML → DOCX (with optional style restore)
 *   POST /api/convert/docx-to-markdown   DOCX → Markdown text
 *   POST /api/convert/pdf-to-text        PDF  → plain text
 * </pre>
 */
@RestController
@RequestMapping("/api/convert")
@RequiredArgsConstructor
@Tag(name = "Conversion", description = "Document format conversion endpoints")
public class ConvertController {

    private final ConversionService conversionService;

    // -----------------------------------------------------------------------
    //  DOCX → HTML
    // -----------------------------------------------------------------------

    @PostMapping(
        value = "/docx-to-html",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Convert DOCX to HTML",
        description = """
            Converts an uploaded .docx file to HTML and extracts the full StyleRegistry.
            
            The returned `docId` can be passed to `html-to-docx` to restore styles
            when converting the (potentially AI-edited) HTML back to DOCX.
            """
    )
    @ApiResponse(responseCode = "200", description = "Conversion successful")
    @ApiResponse(responseCode = "422", description = "Corrupt or unsupported file")
    public ResponseEntity<DocxToHtmlResponse> docxToHtml(
        @Parameter(description = "The .docx file to convert", required = true)
        @RequestParam("file") MultipartFile file
    ) {
        DocxToHtmlResponse response = conversionService.docxToHtml(file);
        return ResponseEntity.ok(response);
    }

    // -----------------------------------------------------------------------
    //  HTML → DOCX
    // -----------------------------------------------------------------------

    @PostMapping(
        value = "/html-to-docx",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )
    @Operation(
        summary = "Convert HTML to DOCX",
        description = """
            Converts (X)HTML to a .docx binary.
            
            Pass `doc_id` to restore the original document's styles from a previous
            `docx-to-html` call. Alternatively, supply an inline `style_registry`.
            If neither is provided, default styles are used.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "DOCX binary",
        content = @Content(schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> htmlToDocx(@Valid @RequestBody HtmlToDocxRequest request) {
        byte[] docx = conversionService.htmlToDocx(
            request.getHtml(),
            request.getDocId(),
            request.getStyleRegistry(),
            request.getBaseUrl()
        );
        return ResponseEntity.ok()
            .headers(downloadHeaders("converted.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .body(docx);
    }

    // -----------------------------------------------------------------------
    //  DOCX → Markdown
    // -----------------------------------------------------------------------

    @PostMapping(
        value = "/docx-to-markdown",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Convert DOCX to Markdown",
        description = """
            Extracts content from a .docx file and converts it to GitHub-Flavored Markdown.
            Heading structure, bold/italic inline formatting, tables, and lists are preserved.
            """
    )
    public ResponseEntity<TextResponse> docxToMarkdown(
        @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.ok(conversionService.docxToMarkdown(file));
    }

    // -----------------------------------------------------------------------
    //  PDF → Text
    // -----------------------------------------------------------------------

    @PostMapping(
        value = "/pdf-to-text",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Extract text from PDF",
        description = """
            Extracts plain text from a PDF file using Apache PDFBox.
            Preserves reading order where possible.
            
            Note: scanned / image-only PDFs will return empty text.
            Integrate Tesseract OCR as a post-processing step for those cases.
            """
    )
    public ResponseEntity<TextResponse> pdfToText(
        @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.ok(conversionService.pdfToText(file));
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private HttpHeaders downloadHeaders(String filename, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDisposition(
            ContentDisposition.attachment().filename(filename).build());
        return headers;
    }
}
