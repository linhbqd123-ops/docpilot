package io.docpilot.mcp.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.model.session.DocumentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for {@link DocumentSession} objects.
 * Uses a write-through in-memory cache backed by disk JSON.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class DocumentSessionStore {

    private final AppProperties props;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, DocumentSession> cache = new ConcurrentHashMap<>();

    public void save(DocumentSession session) {
        cache.put(session.getSessionId(), session);
        persist(sessionPath(session.getSessionId()), session);
    }

    public Optional<DocumentSession> find(String sessionId) {
        DocumentSession cached = cache.get(sessionId);
        if (cached != null) return Optional.of(cached);

        Path file = sessionPath(sessionId);
        if (!Files.exists(file)) return Optional.empty();

        try {
            DocumentSession loaded = objectMapper.readValue(file.toFile(), DocumentSession.class);
            cache.put(sessionId, loaded);
            return Optional.of(loaded);
        } catch (IOException e) {
            log.warn("Cannot deserialise session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String sessionId) {
        cache.remove(sessionId);
        deleteSilently(sessionPath(sessionId));
    }

    /**
     * Saves a raw DOCX byte snapshot alongside the session for rollback support.
     */
    public void saveDocxSnapshot(String sessionId, byte[] docxBytes) {
        Path snapshotDir = props.storage().sessionsPath().resolve(sessionId);
        try {
            Files.createDirectories(snapshotDir);
            Files.write(snapshotDir.resolve("original.docx"), docxBytes);
        } catch (IOException e) {
            log.error("Cannot save DOCX snapshot for session {}: {}", sessionId, e.getMessage());
        }
    }

    public Optional<byte[]> findDocxSnapshot(String sessionId) {
        Path snap = props.storage().sessionsPath().resolve(sessionId).resolve("original.docx");
        if (!Files.exists(snap)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(snap));
        } catch (IOException e) {
            log.warn("Cannot read DOCX snapshot for session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private Path sessionPath(String sessionId) {
        return props.storage().sessionsPath().resolve(sessionId + ".session.json");
    }

    private void persist(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(path.toFile(), value);
        } catch (IOException e) {
            log.error("Failed to persist session to {}: {}", path, e.getMessage());
        }
    }

    private void deleteSilently(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException ignored) {}
    }
}
