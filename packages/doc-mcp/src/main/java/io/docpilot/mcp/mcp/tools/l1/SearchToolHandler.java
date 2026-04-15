package io.docpilot.mcp.mcp.tools.l1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.mcp.ToolDefinition;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * L1 search tools: find_text, find_regex, get_context_window.
 */
@Component
@RequiredArgsConstructor
public class SearchToolHandler {

    private final ObjectMapper objectMapper;
    private final DocumentSessionStore sessionStore;
    private final AnchorService anchorService;

    public List<ToolDefinition> definitions() {
        return List.of(
            new ToolDefinition(
                "find_text",
                "Searches for an exact text string across all blocks in the document. Returns matching block ids and text excerpts.",
                findTextSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    String query = params.path("query").asText();
                    boolean caseSensitive = params.path("case_sensitive").asBoolean(false);
                    Map<String, DocumentComponent> index = anchorService.buildIndex(s.getRoot());

                    ArrayNode results = objectMapper.createArrayNode();
                    index.values().stream()
                        .filter(c -> c.getContentProps() != null && c.getContentProps().getText() != null)
                        .filter(c -> {
                            String text = c.getContentProps().getText();
                            return caseSensitive
                                ? text.contains(query)
                                : text.toLowerCase().contains(query.toLowerCase());
                        })
                        .limit(50)
                        .forEach(c -> {
                            ObjectNode match = objectMapper.createObjectNode();
                            match.put("blockId", c.getId());
                            match.put("type",    c.getType() != null ? c.getType().name() : null);
                            match.put("logicalPath", c.getAnchor() != null ? c.getAnchor().getLogicalPath() : null);
                            String fullText = c.getContentProps().getText();
                            // Return a context snippet (±50 chars around the match)
                            int idx = caseSensitive
                                ? fullText.indexOf(query)
                                : fullText.toLowerCase().indexOf(query.toLowerCase());
                            int start = Math.max(0, idx - 50);
                            int end   = Math.min(fullText.length(), idx + query.length() + 50);
                            match.put("excerpt", (start > 0 ? "…" : "") + fullText.substring(start, end) + (end < fullText.length() ? "…" : ""));
                            results.add(match);
                        });
                    ObjectNode out = objectMapper.createObjectNode();
                    out.set("matches", results);
                    out.put("count", results.size());
                    return out;
                },
                "L1", true
            ),
            new ToolDefinition(
                "find_regex",
                "Searches for blocks matching a Java-compatible regular expression. Returns up to 50 matching block ids and excerpts.",
                findRegexSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    String pattern = params.path("pattern").asText();
                    java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern,
                        java.util.regex.Pattern.CASE_INSENSITIVE);
                    Map<String, DocumentComponent> index = anchorService.buildIndex(s.getRoot());

                    ArrayNode results = objectMapper.createArrayNode();
                    index.values().stream()
                        .filter(c -> c.getContentProps() != null && c.getContentProps().getText() != null)
                        .filter(c -> regex.matcher(c.getContentProps().getText()).find())
                        .limit(50)
                        .forEach(c -> {
                            ObjectNode match = objectMapper.createObjectNode();
                            match.put("blockId", c.getId());
                            match.put("type", c.getType() != null ? c.getType().name() : null);
                            match.put("textExcerpt", excerpt(c.getContentProps().getText(), 200));
                            results.add(match);
                        });
                    return objectMapper.createObjectNode().set("matches", results);
                },
                "L1", true
            ),
            new ToolDefinition(
                "get_context_window",
                "Returns the block identified by block_id plus its N preceding and N following siblings, providing a local context window.",
                contextWindowSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    String blockId = params.path("block_id").asText();
                    int windowSize = params.path("window_size").asInt(2);

                    Map<String, DocumentComponent> index = anchorService.buildIndex(s.getRoot());
                    DocumentComponent target = index.get(blockId);
                    if (target == null) throw new IllegalArgumentException("Block not found: " + blockId);

                    // Find siblings from parent
                    DocumentComponent parent = target.getParentId() != null ? index.get(target.getParentId()) : null;
                    ArrayNode window = objectMapper.createArrayNode();
                    if (parent != null && parent.getChildren() != null) {
                        List<DocumentComponent> siblings = parent.getChildren();
                        int idx = -1;
                        for (int i = 0; i < siblings.size(); i++) {
                            if (siblings.get(i).getId().equals(blockId)) { idx = i; break; }
                        }
                        if (idx >= 0) {
                            int from = Math.max(0, idx - windowSize);
                            int to   = Math.min(siblings.size(), idx + windowSize + 1);
                            for (int i = from; i < to; i++) {
                                DocumentComponent sib = siblings.get(i);
                                ObjectNode node = objectMapper.createObjectNode();
                                node.put("blockId",  sib.getId());
                                node.put("type",     sib.getType() != null ? sib.getType().name() : null);
                                node.put("isTarget", sib.getId().equals(blockId));
                                if (sib.getContentProps() != null) node.put("text", excerpt(sib.getContentProps().getText(), 300));
                                window.add(node);
                            }
                        }
                    }
                    return objectMapper.createObjectNode().set("window", window);
                },
                "L1", true
            )
        );
    }

    private String excerpt(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "…" : text;
    }

    private DocumentSession requireSession(com.fasterxml.jackson.databind.JsonNode params) {
        String sid = params.path("session_id").asText();
        return sessionStore.find(sid).orElseThrow(() -> new IllegalArgumentException("Session not found: " + sid));
    }

    private ObjectNode findTextSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("query").put("type", "string").put("description", "Text to search for");
        props.putObject("case_sensitive").put("type", "boolean").put("description", "Default false");
        root.putArray("required").add("session_id").add("query");
        return root;
    }

    private ObjectNode findRegexSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("pattern").put("type", "string").put("description", "Java regex pattern");
        root.putArray("required").add("session_id").add("pattern");
        return root;
    }

    private ObjectNode contextWindowSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("block_id").put("type", "string");
        props.putObject("window_size").put("type", "integer").put("description", "Number of siblings on each side; default 2");
        root.putArray("required").add("session_id").add("block_id");
        return root;
    }
}
