package io.docpilot.mcp.mcp.tools.l1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.engine.patch.PatchEngine;
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
 * L1 content tools: get_text_runs, replace_text_range, insert_text_at, delete_text_range.
 * These are primitive text-editing tools. The model should prefer L3/L2 tools.
 */
@Component
@RequiredArgsConstructor
public class ContentToolHandler {

    private final ObjectMapper objectMapper;
    private final DocumentSessionStore sessionStore;
    private final AnchorService anchorService;

    public List<ToolDefinition> definitions() {
        return List.of(
            new ToolDefinition(
                "get_text_runs",
                "Returns all TEXT_RUN children of a paragraph block with their text and inline formatting.",
                blockSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    String blockId = params.path("block_id").asText();
                    Map<String, DocumentComponent> index = anchorService.buildIndex(s.getRoot());
                    DocumentComponent block = index.get(blockId);
                    if (block == null) throw new IllegalArgumentException("Block not found: " + blockId);

                    var arr = objectMapper.createArrayNode();
                    if (block.getChildren() != null) {
                        block.getChildren().stream()
                            .filter(c -> c.getType() == ComponentType.TEXT_RUN)
                            .forEach(run -> {
                                ObjectNode runNode = objectMapper.createObjectNode();
                                runNode.put("runId", run.getId());
                                runNode.put("text",  run.getContentProps() != null ? run.getContentProps().getText() : "");
                                if (run.getLayoutProps() != null) {
                                    var lp = run.getLayoutProps();
                                    if (lp.getBold() != null)      runNode.put("bold",      lp.getBold());
                                    if (lp.getItalic() != null)    runNode.put("italic",    lp.getItalic());
                                    if (lp.getUnderline() != null) runNode.put("underline", lp.getUnderline());
                                    if (lp.getFontAscii() != null) runNode.put("font",      lp.getFontAscii());
                                    if (lp.getFontSizePt() != null) runNode.put("fontSizePt", lp.getFontSizePt());
                                    if (lp.getColor() != null)     runNode.put("color",     lp.getColor());
                                }
                                arr.add(runNode);
                            });
                    }
                    return objectMapper.createObjectNode().set("runs", arr);
                },
                "L1", true
            )
        );
    }

    private DocumentSession requireSession(com.fasterxml.jackson.databind.JsonNode params) {
        String sid = params.path("session_id").asText();
        return sessionStore.find(sid).orElseThrow(() -> new IllegalArgumentException("Session not found: " + sid));
    }

    private ObjectNode blockSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("block_id").put("type", "string");
        root.putArray("required").add("session_id").add("block_id");
        return root;
    }
}
