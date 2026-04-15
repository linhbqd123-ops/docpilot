package io.docpilot.mcp.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.model.legacy.StyleRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for {@link StyleRegistry} metadata.
 * Write-through to disk + L1 in-memory cache.
 */
@Repository
@Slf4j
public class RegistryStore {

    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private final ConcurrentHashMap<String, StyleRegistry> registryCache = new ConcurrentHashMap<>();

    public RegistryStore(AppProperties props, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ─── StyleRegistry ────────────────────────────────────────────────────────

    public void saveRegistry(StyleRegistry registry) {
        registryCache.put(registry.getDocId(), registry);
        jdbcTemplate.update(
            """
            INSERT INTO registries (doc_id, filename, extracted_at, payload_json, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(doc_id) DO UPDATE SET
                filename = excluded.filename,
                extracted_at = excluded.extracted_at,
                payload_json = excluded.payload_json,
                updated_at = excluded.updated_at
            """,
            registry.getDocId(),
            registry.getFilename(),
            registry.getExtractedAt() != null ? registry.getExtractedAt().toString() : null,
            toJson(registry),
            java.time.Instant.now().toString()
        );
        log.debug("StyleRegistry saved: docId={}", registry.getDocId());
    }

    public Optional<StyleRegistry> findRegistry(String docId) {
        StyleRegistry cached = registryCache.get(docId);
        if (cached != null) {
            return Optional.of(cached);
        }

        return jdbcTemplate.query(
            "SELECT payload_json FROM registries WHERE doc_id = ?",
            rs -> {
                if (!rs.next()) {
                    return Optional.<StyleRegistry>empty();
                }
                try {
                    StyleRegistry loaded = objectMapper.readValue(rs.getString("payload_json"), StyleRegistry.class);
                    registryCache.put(docId, loaded);
                    return Optional.of(loaded);
                } catch (IOException e) {
                    log.warn("Cannot deserialize registry {} from SQLite: {}", docId, e.getMessage());
                    return Optional.<StyleRegistry>empty();
                }
            },
            docId
        );
    }

    public void deleteRegistry(String docId) {
        registryCache.remove(docId);
        jdbcTemplate.update("DELETE FROM registries WHERE doc_id = ?", docId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise registry payload", e);
        }
    }
}
