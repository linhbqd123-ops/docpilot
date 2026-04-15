package io.docpilot.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A single MCP tool definition.
 *
 * @param name        tool name (snake_case, globally unique within this server)
 * @param description human-readable description for the model
 * @param inputSchema JSON Schema object describing the tool's parameters
 * @param handler     function that executes the tool: (params, sessionId) → result JsonNode
 * @param layer       "L1" | "L2" | "L3"
 * @param readOnly    if true, this tool never mutates the document — safe in ask mode
 */
public record ToolDefinition(
    String name,
    String description,
    ObjectNode inputSchema,
    ToolHandler handler,
    String layer,
    boolean readOnly
) {}
