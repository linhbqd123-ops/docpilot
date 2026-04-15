package io.docpilot.mcp.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.model.legacy.DocumentStructure;
import io.docpilot.mcp.model.legacy.StyleRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for {@link StyleRegistry} and {@link DocumentStructure}.
 * Write-through to disk + L1 in-memory cache.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class RegistryStore {

    private final AppProperties props;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, StyleRegistry>    registryCache  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DocumentStructure> structureCache = new ConcurrentHashMap<>();

    // ─── StyleRegistry ────────────────────────────────────────────────────────

    public void saveRegistry(StyleRegistry registry) {
        registryCache.put(registry.getDocId(), registry);
        persist(registryPath(registry.getDocId()), registry);
        log.debug("StyleRegistry saved: docId={}", registry.getDocId());
    }

    public Optional<StyleRegistry> findRegistry(String docId) {
        StyleRegistry cached = registryCache.get(docId);
        if (cached != null) return Optional.of(cached);

        Path file = registryPath(docId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            StyleRegistry loaded = objectMapper.readValue(file.toFile(), StyleRegistry.class);
            registryCache.put(docId, loaded);
            return Optional.of(loaded);
        } catch (IOException e) {
            log.warn("Cannot deserialize registry from {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    public void deleteRegistry(String docId) {
        registryCache.remove(docId);
        deleteSilently(registryPath(docId));
    }

    // ─── DocumentStructure ───────────────────────────────────────────────────

    public void saveStructure(DocumentStructure structure) {
        structureCache.put(structure.getDocId(), structure);
        persist(structurePath(structure.getDocId()), structure);
        log.debug("DocumentStructure saved: docId={}", structure.getDocId());
    }

    public Optional<DocumentStructure> findStructure(String docId) {
        DocumentStructure cached = structureCache.get(docId);
        if (cached != null) return Optional.of(cached);

        Path file = structurePath(docId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            DocumentStructure loaded = objectMapper.readValue(file.toFile(), DocumentStructure.class);
            structureCache.put(docId, loaded);
            return Optional.of(loaded);
        } catch (IOException e) {
            log.warn("Cannot deserialize structure from {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    // ─── internal ─────────────────────────────────────────────────────────────

    private Path registryPath(String docId) {
        return props.storage().registryPath().resolve(docId + ".registry.json");
    }

    private Path structurePath(String docId) {
        return props.storage().registryPath().resolve(docId + ".structure.json");
    }

    private void persist(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value);
        } catch (IOException e) {
            log.error("Failed to persist {} — in-memory cache still active: {}", target, e.getMessage());
        }
    }

    private void deleteSilently(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", path, e.getMessage());
        }
    }
}
