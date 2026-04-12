package io.docpilot.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;

/**
 * Centralised application properties — bound from application.yml under prefix "docpilot".
 * All file paths are resolved at startup so runtime failures surface immediately.
 */
@ConfigurationProperties(prefix = "docpilot")
@Validated
public record AppProperties(

    @NotNull Storage storage,
    @NotNull Processing processing

) {
    public record Storage(
        /** Root directory for DOCX uploads and converted artefacts. */
        @NotBlank String uploadDir,
        /** Directory where style registry JSON files are persisted. */
        @NotBlank String registryDir
    ) {
        public Path uploadPath() { return Path.of(uploadDir); }
        public Path registryPath() { return Path.of(registryDir); }
    }

    public record Processing(
        /** Maximum accepted file size in bytes (default 50 MB). */
        @Min(1) long maxFileSizeBytes,
        /** Whether to embed images as base64 in HTML output (vs. writing files to disk). */
        boolean embedImagesInHtml
    ) {}
}
