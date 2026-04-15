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
    @NotNull Persistence persistence,
    @NotNull Personalization personalization,
    @NotNull Processing processing,
    @NotNull Mcp mcp
) {
    public record Storage(
        @NotBlank String dataDir,
        @NotBlank String uploadDir,
        @NotBlank String registryDir,
        @NotBlank String sessionsDir,
        @NotBlank String revisionsDir,
        @NotBlank String legacyArchiveDir
    ) {
        public Path dataPath()      { return Path.of(dataDir).toAbsolutePath().normalize(); }
        public Path uploadPath()    { return Path.of(uploadDir); }
        public Path registryPath()  { return Path.of(registryDir); }
        public Path sessionsPath()  { return Path.of(sessionsDir); }
        public Path revisionsPath() { return Path.of(revisionsDir); }
        public Path legacyArchivePath() { return Path.of(legacyArchiveDir); }
    }

    public record Persistence(
        @NotBlank String sqlitePath,
        @Min(1) int poolSize,
        @Min(1000) int busyTimeoutMs,
        @Min(1024) long mmapSizeBytes,
        @Min(1024) long cacheSizeKb,
        @Min(1) int walAutocheckpointPages,
        @Min(1024) long journalSizeLimitBytes,
        boolean migrateLegacyJsonOnStartup,
        boolean archiveLegacyJson,
        boolean optimizeOnStartup
    ) {
        public Path sqliteFilePath() { return Path.of(sqlitePath).toAbsolutePath().normalize(); }
    }

    public record Personalization(
        @NotBlank String provider,
        @NotBlank String qdrantUrl,
        @NotBlank String qdrantCollection,
        @Min(1) int embeddingDimensions,
        String qdrantApiKey,
        @NotBlank String embeddingProvider,
        String embeddingApiUrl,
        String embeddingModel,
        String embeddingApiKey,
        @Min(1000) int requestTimeoutMs,
        @Min(1) int maxSearchResults,
        @Min(128) int maxIndexedChars
    ) {}

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
