package io.docpilot.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Functional interface for MCP tool handlers.
 *
 * @param params    the {@code arguments} node from the {@code tools/call} request
 * @return          JSON result node; never null — return an empty ObjectNode on success with no data
 * @throws Exception any error; the MCP controller converts this to a JSON-RPC error response
 */
@FunctionalInterface
public interface ToolHandler {
    JsonNode handle(JsonNode params) throws Exception;
}
