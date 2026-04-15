package io.docpilot.mcp.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "docpilot")
@Validated
public record AppProperties(
    @NotNull Storage storage,
    @NotNull Processing processing,
    @NotNull Mcp mcp
) {
    public record Storage(
        @NotBlank String uploadDir,
        @NotBlank String registryDir,
        @NotBlank String sessionsDir,
        @NotBlank String revisionsDir
    ) {
        public Path uploadPath()    { return Path.of(uploadDir); }
        public Path registryPath()  { return Path.of(registryDir); }
        public Path sessionsPath()  { return Path.of(sessionsDir); }
        public Path revisionsPath() { return Path.of(revisionsDir); }
    }

    public record Processing(
        @Min(1) long maxFileSizeBytes,
        boolean embedImagesInHtml
    ) {}

    public record Mcp(
        @NotBlank String serverName,
        @NotBlank String serverVersion,
        @NotBlank String protocolVersion
    ) {}
}
