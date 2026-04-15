package io.docpilot.mcp.mcp.tools.l1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.mcp.ToolDefinition;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * L1 style tools: get_style.
 * More advanced style tools (create_style, update_style_definition) are in L2.
 */
@Component
@RequiredArgsConstructor
public class StyleToolHandler {

    private final ObjectMapper objectMapper;
    private final DocumentSessionStore sessionStore;
    private final io.docpilot.mcp.storage.RegistryStore registryStore;

    public List<ToolDefinition> definitions() {
        return List.of(
            new ToolDefinition(
                "get_style",
                "Returns details of a style from the document's StyleRegistry. Pass the OOXML styleId, e.g. 'Heading1', 'Normal', 'TableGrid'.",
                getStyleSchema(),
                params -> {
                    String sessionId = params.path("session_id").asText();
                    String styleId   = params.path("style_id").asText();

                    DocumentSession s = sessionStore.find(sessionId)
                        .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
                    String docId = s.getDocId();

                    return registryStore.findRegistry(docId)
                        .map(reg -> {
                            ObjectNode out = objectMapper.createObjectNode();
                            if (reg.getStyles() != null && reg.getStyles().containsKey(styleId)) {
                                out.set("style", objectMapper.valueToTree(reg.getStyles().get(styleId)));
                            } else {
                                out.put("error", "Style not found: " + styleId);
                            }
                            return (com.fasterxml.jackson.databind.JsonNode) out;
                        })
                        .orElseGet(() -> {
                            ObjectNode node = objectMapper.createObjectNode();
                            node.put("error", "StyleRegistry not found for docId=" + docId);
                            return node;
                        });
                },
                "L1", true
            ),
            new ToolDefinition(
                "list_styles",
                "Lists all styles in the document's StyleRegistry. Returns style IDs, names, and types.",
                sessionIdSchema(),
                params -> {
                    String sessionId = params.path("session_id").asText();
                    DocumentSession s = sessionStore.find(sessionId)
                        .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

                    return registryStore.findRegistry(s.getDocId())
                        .map(reg -> {
                            ArrayNode arr = objectMapper.createArrayNode();
                            if (reg.getStyles() != null) {
                                reg.getStyles().forEach((sid, entry) -> {
                                    ObjectNode node = objectMapper.createObjectNode();
                                    node.put("styleId", sid);
                                    node.put("name", entry.getName());
                                    node.put("type", entry.getType());
                                    node.put("basedOn", entry.getBasedOn());
                                    arr.add(node);
                                });
                            }
                            return (com.fasterxml.jackson.databind.JsonNode) arr;
                        })
                        .orElse(objectMapper.createArrayNode());
                },
                "L1", true
            )
        );
    }

    private ObjectNode getStyleSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("style_id").put("type", "string").put("description", "OOXML style ID, e.g. 'Heading1'");
        root.putArray("required").add("session_id").add("style_id");
        return root;
    }

    private ObjectNode sessionIdSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("session_id").put("type", "string");
        root.putArray("required").add("session_id");
        return root;
    }
}
