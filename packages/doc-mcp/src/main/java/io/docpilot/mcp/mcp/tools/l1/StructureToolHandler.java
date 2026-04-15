package io.docpilot.mcp.mcp.tools.l1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.mcp.ToolDefinition;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * L1 structure tools: get_block_tree, get_block_by_id, get_parent_block, get_child_blocks.
 */
@Component
@RequiredArgsConstructor
public class StructureToolHandler {

    private final ObjectMapper objectMapper;
    private final DocumentSessionStore sessionStore;
    private final AnchorService anchorService;

    public List<ToolDefinition> definitions() {
        return List.of(
            new ToolDefinition(
                "get_block_tree",
                "Returns the full component tree (outline) of a document session. Each node includes id, type, styleRef, contentProps.text (truncated to 200 chars), and child count.",
                sessionIdSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    return serializeTree(s.getRoot(), 0, 4);
                },
                "L1", true
            ),
            new ToolDefinition(
                "get_block_by_id",
                "Returns a single component (block) by its stableId, including its full content and children.",
                blockIdSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    String blockId = params.path("block_id").asText();
                    Map<String, DocumentComponent> index = anchorService.buildIndex(s.getRoot());
                    DocumentComponent c = index.get(blockId);
                    if (c == null) throw new IllegalArgumentException("Block not found: " + blockId);
                    return objectMapper.valueToTree(c);
                },
                "L1", true
            ),
            new ToolDefinition(
                "get_child_blocks",
                "Returns the immediate children of a block identified by block_id.",
                blockIdSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    String blockId = params.path("block_id").asText();
                    Map<String, DocumentComponent> index = anchorService.buildIndex(s.getRoot());
                    DocumentComponent c = index.get(blockId);
                    if (c == null) throw new IllegalArgumentException("Block not found: " + blockId);
                    ArrayNode arr = objectMapper.createArrayNode();
                    if (c.getChildren() != null) {
                        c.getChildren().forEach(child -> arr.add(serializeTree(child, 0, 1)));
                    }
                    return arr;
                },
                "L1", true
            )
        );
    }

    private ObjectNode serializeTree(DocumentComponent c, int depth, int maxDepth) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id",   c.getId());
        node.put("type", c.getType() != null ? c.getType().name() : null);
        if (c.getStyleRef() != null) node.put("styleId", c.getStyleRef().getStyleId());
        if (c.getContentProps() != null && c.getContentProps().getText() != null) {
            String text = c.getContentProps().getText();
            node.put("text", text.length() > 200 ? text.substring(0, 200) + "…" : text);
        }
        if (c.getAnchor() != null) node.put("logicalPath", c.getAnchor().getLogicalPath());

        int childCount = c.getChildren() != null ? c.getChildren().size() : 0;
        node.put("childCount", childCount);

        if (depth < maxDepth && c.getChildren() != null && !c.getChildren().isEmpty()) {
            ArrayNode children = node.putArray("children");
            c.getChildren().forEach(ch -> children.add(serializeTree(ch, depth + 1, maxDepth)));
        }
        return node;
    }

    private DocumentSession requireSession(com.fasterxml.jackson.databind.JsonNode params) {
        String sessionId = params.path("session_id").asText();
        return sessionStore.find(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    private ObjectNode sessionIdSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string").put("description", "Document session ID");
        root.putArray("required").add("session_id");
        return root;
    }

    private ObjectNode blockIdSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string").put("description", "Document session ID");
        props.putObject("block_id").put("type", "string").put("description", "stableId of the block");
        root.putArray("required").add("session_id").add("block_id");
        return root;
    }
}
