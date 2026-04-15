package io.docpilot.mcp.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.model.session.DocumentSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for {@link DocumentSession} objects.
 * Uses a write-through in-memory cache backed by disk JSON.
 */
@Repository
@Slf4j
public class DocumentSessionStore {

    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private final ConcurrentHashMap<String, DocumentSession> cache = new ConcurrentHashMap<>();

    public DocumentSessionStore(AppProperties props, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(DocumentSession session) {
        cache.put(session.getSessionId(), session);
        String now = java.time.Instant.now().toString();
        jdbcTemplate.update(
            """
            INSERT INTO sessions (
                session_id, doc_id, filename, original_filename, state, current_revision_id,
                created_at, last_modified_at, word_count, paragraph_count, table_count, image_count,
                section_count, payload_json, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                doc_id = excluded.doc_id,
                filename = excluded.filename,
                original_filename = excluded.original_filename,
                state = excluded.state,
                current_revision_id = excluded.current_revision_id,
                created_at = excluded.created_at,
                last_modified_at = excluded.last_modified_at,
                word_count = excluded.word_count,
                paragraph_count = excluded.paragraph_count,
                table_count = excluded.table_count,
                image_count = excluded.image_count,
                section_count = excluded.section_count,
                payload_json = excluded.payload_json,
                updated_at = excluded.updated_at
            """,
            session.getSessionId(),
            session.getDocId(),
            session.getFilename(),
            session.getOriginalFilename(),
            session.getState() != null ? session.getState().name() : null,
            session.getCurrentRevisionId(),
            session.getCreatedAt() != null ? session.getCreatedAt().toString() : null,
            session.getLastModifiedAt() != null ? session.getLastModifiedAt().toString() : null,
            session.getWordCount(),
            session.getParagraphCount(),
            session.getTableCount(),
            session.getImageCount(),
            session.getSectionCount(),
            toJson(session),
            now
        );
    }

    public Optional<DocumentSession> find(String sessionId) {
        DocumentSession cached = cache.get(sessionId);
        if (cached != null) return Optional.of(cached);

        return jdbcTemplate.query(
            "SELECT payload_json FROM sessions WHERE session_id = ?",
            rs -> {
                if (!rs.next()) {
                    return Optional.<DocumentSession>empty();
                }
                try {
                    DocumentSession loaded = objectMapper.readValue(rs.getString("payload_json"), DocumentSession.class);
                    cache.put(sessionId, loaded);
                    return Optional.of(loaded);
                } catch (IOException e) {
                    log.warn("Cannot deserialise session {} from SQLite: {}", sessionId, e.getMessage());
                    return Optional.<DocumentSession>empty();
                }
            },
            sessionId
        );
    }

    public void delete(String sessionId) {
        cache.remove(sessionId);
        jdbcTemplate.update("DELETE FROM sessions WHERE session_id = ?", sessionId);
    }

    /**
     * Saves a raw DOCX byte snapshot alongside the session for rollback support.
     */
    public void saveDocxSnapshot(String sessionId, byte[] docxBytes) {
        jdbcTemplate.update(
            """
            INSERT INTO session_binary_assets (session_id, asset_name, payload, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(session_id, asset_name) DO UPDATE SET
                payload = excluded.payload,
                updated_at = excluded.updated_at
            """,
            sessionId,
            "original.docx",
            docxBytes,
            java.time.Instant.now().toString()
        );
    }

    public Optional<byte[]> findDocxSnapshot(String sessionId) {
        return jdbcTemplate.query(
            "SELECT payload FROM session_binary_assets WHERE session_id = ? AND asset_name = ?",
            rs -> rs.next() ? Optional.ofNullable(rs.getBytes("payload")) : Optional.<byte[]>empty(),
            sessionId,
            "original.docx"
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise session payload", e);
        }
    }
}
