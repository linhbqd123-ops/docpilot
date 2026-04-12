package io.docpilot.processor.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.processor.config.AppProperties;
import io.docpilot.processor.model.DocumentStructure;
import io.docpilot.processor.model.StyleRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for {@link StyleRegistry} and {@link DocumentStructure} objects.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Write-through to disk as JSON (survives restarts).</li>
 *   <li>L1 in-memory cache for zero-latency reads during a session.</li>
 * </ol>
 *
 * <p>In a multi-instance / cloud deployment, switch to a shared store
 * (Redis, PostgreSQL JSONB) by implementing the same interface.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class RegistryStore {

    private final AppProperties props;
    private final ObjectMapper objectMapper;

    // In-memory caches
    private final ConcurrentHashMap<String, StyleRegistry> registryCache  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DocumentStructure> structureCache = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // StyleRegistry
    // -----------------------------------------------------------------------

    public void saveRegistry(StyleRegistry registry) {
        String docId = registry.getDocId();
        registryCache.put(docId, registry);
        persist(registryPath(docId), registry);
        log.debug("StyleRegistry saved: docId={}", docId);
    }

    public Optional<StyleRegistry> findRegistry(String docId) {
        StyleRegistry cached = registryCache.get(docId);
        if (cached != null) return Optional.of(cached);

        // Fall back to disk
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

    // -----------------------------------------------------------------------
    // DocumentStructure
    // -----------------------------------------------------------------------

    public void saveStructure(DocumentStructure structure) {
        String docId = structure.getDocId();
        structureCache.put(docId, structure);
        persist(structurePath(docId), structure);
        log.debug("DocumentStructure saved: docId={}", docId);
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

    // -----------------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------------

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
            // Non-fatal: in-memory cache is still valid; log and continue.
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
