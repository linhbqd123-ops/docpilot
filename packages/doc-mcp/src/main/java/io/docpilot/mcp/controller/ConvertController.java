package io.docpilot.mcp.controller;

import io.docpilot.mcp.model.request.HtmlToDocxRequest;
import io.docpilot.mcp.model.response.DocxToHtmlResponse;
import io.docpilot.mcp.model.response.TextResponse;
import io.docpilot.mcp.service.ConversionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for document format conversion (legacy endpoints, ported from doc-processor).
 *
 * POST /api/convert/docx-to-html      DOCX → HTML + StyleRegistry
 * POST /api/convert/html-to-docx      HTML → DOCX
 * POST /api/convert/docx-to-markdown  DOCX → Markdown
 * POST /api/convert/pdf-to-text       PDF  → plain text
 */
@RestController
@RequestMapping("/api/convert")
@RequiredArgsConstructor
public class ConvertController {

    private final ConversionService conversionService;

    @PostMapping(value = "/docx-to-html", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocxToHtmlResponse> docxToHtml(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(conversionService.docxToHtml(file));
    }

    @PostMapping(value = "/html-to-docx", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    public ResponseEntity<byte[]> htmlToDocx(@Valid @RequestBody HtmlToDocxRequest request) {
        byte[] docx = conversionService.htmlToDocx(
            request.getHtml(), request.getDocId(), request.getStyleRegistry(), request.getBaseUrl());
        return ResponseEntity.ok()
            .headers(downloadHeaders("converted.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .body(docx);
    }

    @PostMapping(value = "/docx-to-markdown", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TextResponse> docxToMarkdown(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(conversionService.docxToMarkdown(file));
    }

    @PostMapping(value = "/pdf-to-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TextResponse> pdfToText(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(conversionService.pdfToText(file));
    }

    private HttpHeaders downloadHeaders(String filename, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return headers;
    }
}
