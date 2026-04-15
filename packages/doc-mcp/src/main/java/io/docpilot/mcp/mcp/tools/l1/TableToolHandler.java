package io.docpilot.mcp.mcp.tools.l1;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * L1 table tools: get_table_by_id, update_cell_content, insert_row, delete_row.
 */
@Component
@RequiredArgsConstructor
public class TableToolHandler {

    private final ObjectMapper objectMapper;
    private final DocumentSessionStore sessionStore;
    private final AnchorService anchorService;

    public List<ToolDefinition> definitions() {
        return List.of(
            new ToolDefinition(
                "get_table_by_id",
                "Returns a table's metadata and all its rows/cells with text content. Use block_id of a TABLE component.",
                tableSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    String blockId = params.path("block_id").asText();
                    Map<String, DocumentComponent> index = anchorService.buildIndex(s.getRoot());
                    DocumentComponent tbl = index.get(blockId);
                    if (tbl == null || tbl.getType() != ComponentType.TABLE)
                        throw new IllegalArgumentException("Table not found: " + blockId);

                    ObjectNode out = objectMapper.createObjectNode();
                    out.put("tableId",  tbl.getId());
                    out.put("rowCount", tbl.getChildren() != null ? tbl.getChildren().size() : 0);
                    var rows = out.putArray("rows");
                    int ri = 0;
                    for (DocumentComponent row : tbl.getChildren()) {
                        ObjectNode rowNode = objectMapper.createObjectNode();
                        rowNode.put("rowId",    row.getId());
                        rowNode.put("rowIndex", ri);
                        var cells = rowNode.putArray("cells");
                        int ci = 0;
                        for (DocumentComponent cell : row.getChildren()) {
                            ObjectNode cellNode = objectMapper.createObjectNode();
                            cellNode.put("cellId", cell.getId());
                            cellNode.put("cellIndex", ci);
                            if (cell.getAnchor() != null) cellNode.put("cellAddress", cell.getAnchor().getCellLogicalAddress());
                            cellNode.put("text", cell.getContentProps() != null ? cell.getContentProps().getText() : "");
                            cells.add(cellNode);
                            ci++;
                        }
                        rows.add(rowNode);
                        ri++;
                    }
                    return out;
                },
                "L1", true
            )
        );
    }

    private DocumentSession requireSession(com.fasterxml.jackson.databind.JsonNode params) {
        String sid = params.path("session_id").asText();
        return sessionStore.find(sid).orElseThrow(() -> new IllegalArgumentException("Session not found: " + sid));
    }

    private ObjectNode tableSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("block_id").put("type", "string").put("description", "stableId of the TABLE component");
        root.putArray("required").add("session_id").add("block_id");
        return root;
    }
}
