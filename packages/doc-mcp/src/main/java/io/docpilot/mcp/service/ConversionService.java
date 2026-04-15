package io.docpilot.mcp.service;

import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.converter.*;
import io.docpilot.mcp.exception.ConversionException;
import io.docpilot.mcp.model.legacy.DocumentStructure;
import io.docpilot.mcp.model.legacy.StyleRegistry;
import io.docpilot.mcp.model.response.DocxToHtmlResponse;
import io.docpilot.mcp.model.response.TextResponse;
import io.docpilot.mcp.storage.RegistryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestration service for all document conversion flows (legacy endpoints).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversionService {

    private static final Set<String> ALLOWED_DOCX_TYPES = Set.of(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword",
        "application/octet-stream"
    );

    private static final Set<String> ALLOWED_PDF_TYPES = Set.of(
        "application/pdf",
        "application/octet-stream"
    );

    private final AppProperties props;
    private final DocxToHtmlConverter docxToHtmlConverter;
    private final HtmlToDocxConverter htmlToDocxConverter;
    private final DocxToMarkdownConverter markdownConverter;
    private final PdfToTextConverter pdfToTextConverter;
    private final DocumentStructureExtractor structureExtractor;
    private final RegistryStore registryStore;

    // ─── DOCX → HTML ─────────────────────────────────────────────────────────

    public DocxToHtmlResponse docxToHtml(MultipartFile file) {
        validateDocx(file);
        String docId = UUID.randomUUID().toString();
        String filename = sanitizeFilename(file.getOriginalFilename());

        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            DocxToHtmlConverter.Result result = docxToHtmlConverter.convert(in, docId, filename);
            registryStore.saveRegistry(result.registry());
            saveUploadedFile(file, docId, "original.docx");

            return DocxToHtmlResponse.builder()
                .docId(docId)
                .html(result.html())
                .styleRegistry(result.registry())
                .filename(filename)
                .wordCount(result.wordCount())
                .build();
        } catch (ConversionException ce) {
            throw ce;
        } catch (IOException e) {
            throw new ConversionException("Cannot read uploaded file: " + e.getMessage(), e);
        }
    }

    // ─── HTML → DOCX ─────────────────────────────────────────────────────────

    public byte[] htmlToDocx(String html, String docId, StyleRegistry inlineRegistry, String baseUrl) {
        StyleRegistry registry = null;
        if (inlineRegistry != null) {
            registry = inlineRegistry;
        } else if (docId != null && !docId.isBlank()) {
            registry = registryStore.findRegistry(docId).orElse(null);
            if (registry == null) {
                log.warn("No registry found for docId={}, converting without style restore", docId);
            }
        }
        return htmlToDocxConverter.convert(html, baseUrl, registry);
    }

    // ─── DOCX → Markdown ─────────────────────────────────────────────────────

    public TextResponse docxToMarkdown(MultipartFile file) {
        validateDocx(file);
        String filename = sanitizeFilename(file.getOriginalFilename());

        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            String markdown = markdownConverter.convert(in, filename);
            int words = markdown.isBlank() ? 0 : markdown.trim().split("\\s+").length;

            return TextResponse.builder()
                .docId(UUID.randomUUID().toString())
                .filename(filename)
                .content(markdown)
                .wordCount(words)
                .charCount(markdown.length())
                .build();
        } catch (ConversionException ce) {
            throw ce;
        } catch (IOException e) {
            throw new ConversionException("Cannot read uploaded file: " + e.getMessage(), e);
        }
    }

    // ─── PDF → Text ──────────────────────────────────────────────────────────

    public TextResponse pdfToText(MultipartFile file) {
        validatePdf(file);
        String filename = sanitizeFilename(file.getOriginalFilename());

        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            PdfToTextConverter.Result result = pdfToTextConverter.convert(in, filename);

            return TextResponse.builder()
                .docId(UUID.randomUUID().toString())
                .filename(filename)
                .content(result.text())
                .wordCount(result.wordCount())
                .charCount(result.text().length())
                .build();
        } catch (ConversionException ce) {
            throw ce;
        } catch (IOException e) {
            throw new ConversionException("Cannot read uploaded file: " + e.getMessage(), e);
        }
    }

    // ─── Document Structure ───────────────────────────────────────────────────

    public DocumentStructure extractStructure(MultipartFile file) {
        validateDocx(file);
        String docId   = UUID.randomUUID().toString();
        String filename = sanitizeFilename(file.getOriginalFilename());

        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            DocumentStructure structure = structureExtractor.extract(in, docId, filename);
            registryStore.saveStructure(structure);
            return structure;
        } catch (ConversionException ce) {
            throw ce;
        } catch (IOException e) {
            throw new ConversionException("Cannot read uploaded file: " + e.getMessage(), e);
        }
    }

    // ─── validation helpers ───────────────────────────────────────────────────

    private void validateDocx(MultipartFile file) {
        validateFile(file, ALLOWED_DOCX_TYPES, ".docx");
    }

    private void validatePdf(MultipartFile file) {
        validateFile(file, ALLOWED_PDF_TYPES, ".pdf");
    }

    private void validateFile(MultipartFile file, Set<String> allowedTypes, String expectedExt) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("File must not be empty");
        long maxBytes = props.processing().maxFileSizeBytes();
        if (file.getSize() > maxBytes) {
            throw new ConversionException(
                "File size " + file.getSize() + " bytes exceeds limit of " + maxBytes + " bytes");
        }
        String ct   = file.getContentType();
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (!name.endsWith(expectedExt) && (ct == null || !allowedTypes.contains(ct.toLowerCase()))) {
            throw new ConversionException("Unsupported file type. Expected " + expectedExt + " file.");
        }
    }

    private void saveUploadedFile(MultipartFile file, String docId, String suffix) {
        try {
            Path dir = props.storage().uploadPath().resolve(docId);
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(suffix));
        } catch (IOException e) {
            log.warn("Could not persist original file for docId={}: {}", docId, e.getMessage());
        }
    }

    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) return "document.docx";
        // Keep only safe characters
        return original.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
