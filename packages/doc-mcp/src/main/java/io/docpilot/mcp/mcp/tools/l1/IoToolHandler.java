package io.docpilot.mcp.mcp.tools.l1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.mcp.ToolDefinition;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * L1 IO tools: load_document, get_document_metadata, export_docx.
 */
@Component
@RequiredArgsConstructor
public class IoToolHandler {

    private final ObjectMapper objectMapper;
    private final DocumentSessionStore sessionStore;

    public List<ToolDefinition> definitions() {
        return List.of(
            new ToolDefinition(
                "get_document_metadata",
                "Returns metadata and statistics for a document session: filename, word count, paragraph count, table count, section count, current revision.",
                sessionIdSchema(),
                params -> {
                    String sessionId = params.path("session_id").asText();
                    DocumentSession s = sessionStore.find(sessionId)
                        .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
                    ObjectNode out = objectMapper.createObjectNode();
                    out.put("sessionId",      s.getSessionId());
                    out.put("docId",          s.getDocId());
                    out.put("filename",       s.getFilename());
                    out.put("state",          s.getState() != null ? s.getState().name() : null);
                    out.put("wordCount",      s.getWordCount() != null ? s.getWordCount() : 0);
                    out.put("paragraphCount", s.getParagraphCount() != null ? s.getParagraphCount() : 0);
                    out.put("tableCount",     s.getTableCount() != null ? s.getTableCount() : 0);
                    out.put("imageCount",     s.getImageCount() != null ? s.getImageCount() : 0);
                    out.put("sectionCount",   s.getSectionCount() != null ? s.getSectionCount() : 0);
                    out.put("currentRevisionId", s.getCurrentRevisionId());
                    out.put("createdAt",      s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
                    out.put("lastModifiedAt", s.getLastModifiedAt() != null ? s.getLastModifiedAt().toString() : null);
                    return out;
                },
                "L1", true
            )
        );
    }

    private ObjectNode sessionIdSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string").put("description", "The document session ID");
        root.putArray("required").add("session_id");
        return root;
    }
}
