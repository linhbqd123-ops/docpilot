package io.docpilot.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.mcp.tools.l1.*;
import io.docpilot.mcp.mcp.tools.l2.WorkflowToolHandler;
import io.docpilot.mcp.mcp.tools.l3.AiFacingToolHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Central registry of all MCP tools exposed by this server.
 *
 * <p>Tools are organised into three tiers (plan section 9):
 * <ul>
 *   <li><b>L1</b> — primitive engine tools (fine-grained, composable)</li>
 *   <li><b>L2</b> — composable workflow tools (assembled from L1)</li>
 *   <li><b>L3</b> — AI-facing tools (what the model actually sees)</li>
 * </ul>
 *
 * <p>Only L3 tools (and safe L2 read-only tools) are exposed in the default
 * {@code tools/list} response that gets sent to the AI model.  L1 tools are
 * available for internal composition and testing but are filtered from the AI
 * surface by default.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpToolRegistry {

    private final ObjectMapper objectMapper;

    // L1 handlers
    private final IoToolHandler         ioToolHandler;
    private final StructureToolHandler  structureToolHandler;
    private final ContentToolHandler    contentToolHandler;
    private final SearchToolHandler     searchToolHandler;
    private final TableToolHandler      tableToolHandler;
    private final StyleToolHandler      styleToolHandler;
    private final ChangeToolHandler     changeToolHandler;
    private final ValidationToolHandler validationToolHandler;

    // L2 handlers
    private final WorkflowToolHandler workflowToolHandler;

    // L3 handlers
    private final AiFacingToolHandler aiFacingToolHandler;

    private final Map<String, ToolDefinition> registry = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        registerAll(ioToolHandler.definitions());
        registerAll(structureToolHandler.definitions());
        registerAll(contentToolHandler.definitions());
        registerAll(searchToolHandler.definitions());
        registerAll(tableToolHandler.definitions());
        registerAll(styleToolHandler.definitions());
        registerAll(changeToolHandler.definitions());
        registerAll(validationToolHandler.definitions());
        registerAll(workflowToolHandler.definitions());
        registerAll(aiFacingToolHandler.definitions());
        log.info("MCP tool registry initialised: {} tools registered", registry.size());
    }

    private void registerAll(List<ToolDefinition> defs) {
        for (ToolDefinition def : defs) {
            if (registry.containsKey(def.name())) {
                log.warn("Duplicate tool name: {} — overwriting previous registration", def.name());
            }
            registry.put(def.name(), def);
        }
    }

    // -----------------------------------------------------------------------
    //  Query
    // -----------------------------------------------------------------------

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(registry.get(name));
    }

    /**
     * Returns the tools/list result payload — by default only L3 + read-only L2 tools.
     * Pass {@code includeAll=true} to see L1 as well (for testing).
     */
    public ArrayNode toToolListJson(boolean includeAll) {
        ArrayNode arr = objectMapper.createArrayNode();
        registry.values().stream()
            .filter(t -> includeAll || "L3".equals(t.layer()) || ("L2".equals(t.layer()) && t.readOnly()))
            .forEach(t -> {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("name", t.name());
                node.put("description", t.description());
                node.set("inputSchema", t.inputSchema());
                arr.add(node);
            });
        return arr;
    }

    public Collection<ToolDefinition> all() { return Collections.unmodifiableCollection(registry.values()); }

    // -----------------------------------------------------------------------
    //  Schema builder helpers (used by tool handler classes)
    // -----------------------------------------------------------------------

    public static ObjectNode schema(ObjectMapper om, String... requiredAndProps) {
        ObjectNode root = om.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ArrayNode required = root.putArray("required");

        for (int i = 0; i < requiredAndProps.length - 1; i += 3) {
            String name   = requiredAndProps[i];
            String type   = requiredAndProps[i + 1];
            String desc   = requiredAndProps[i + 2];
            boolean req   = i + 3 < requiredAndProps.length && !requiredAndProps[i + 3].startsWith("?");

            ObjectNode prop = props.putObject(name);
            prop.put("type", type);
            prop.put("description", desc);
            if (req) required.add(name);
        }
        return root;
    }
}
