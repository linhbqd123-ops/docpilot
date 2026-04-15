package io.docpilot.mcp.controller;

import io.docpilot.mcp.converter.PdfToTextConverter;
import io.docpilot.mcp.exception.ConversionException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
public class ImportController {

    private static final Set<String> ALLOWED_PDF_TYPES = Set.of(
        "application/pdf",
        "application/octet-stream"
    );

    private final PdfToTextConverter pdfToTextConverter;

    @PostMapping(value = "/pdf-to-html", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> pdfToHtml(@RequestParam("file") MultipartFile file) {
        validatePdf(file);
        String filename = sanitizeFilename(file.getOriginalFilename());

        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            PdfToTextConverter.Result result = pdfToTextConverter.convert(in, filename);
            return ResponseEntity.ok(Map.of(
                "filename", filename,
                "html", textToHtml(result.text()),
                "word_count", result.wordCount(),
                "page_count", result.pageCount()
            ));
        } catch (ConversionException ce) {
            throw ce;
        } catch (IOException e) {
            throw new ConversionException("Cannot read uploaded file: " + e.getMessage(), e);
        }
    }

    private void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        if (!name.endsWith(".pdf") && !ALLOWED_PDF_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported file type. Expected a PDF file.");
        }
    }

    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "document.pdf";
        }
        return original.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private String textToHtml(String text) {
        List<String> paragraphs = java.util.Arrays.stream(text.split("\\n\\n"))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();

        StringBuilder html = new StringBuilder();
        for (String paragraph : paragraphs) {
            html.append("<p>")
                .append(HtmlUtils.htmlEscape(paragraph).replace("\n", "<br />"))
                .append("</p>");
        }
        return html.toString();
    }
}