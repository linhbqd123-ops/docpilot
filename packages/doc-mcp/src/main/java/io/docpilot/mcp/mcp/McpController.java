package io.docpilot.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MCP JSON-RPC 2.0 controller.
 *
 * POST /mcp  — handles MCP method calls (initialize, tools/list, tools/call, etc.)
 * GET  /mcp/sse — SSE stream for server-initiated notifications
 *
 * Protocol reference: https://spec.modelcontextprotocol.io/specification/2024-11-05/
 */
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@Slf4j
public class McpController {

    private final ObjectMapper objectMapper;
    private final McpToolRegistry toolRegistry;
    private final AppProperties appProperties;

    // JSON-RPC 2.0 error codes
    private static final int PARSE_ERROR     = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS  = -32602;
    private static final int INTERNAL_ERROR  = -32603;

    // SSE emitters (sessionId → emitter)
    private final ConcurrentMap<String, SseEmitter> sseEmitters = new ConcurrentHashMap<>();

    // ─── POST /mcp ────────────────────────────────────────────────────────────

    @PostMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ObjectNode> handleRpc(@RequestBody String body) {
        JsonNode req;
        try {
            req = objectMapper.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.ok(errorResponse(null, PARSE_ERROR, "Parse error: " + e.getMessage()));
        }

        // Basic JSON-RPC 2.0 validation
        if (!req.path("jsonrpc").asText().equals("2.0")) {
            return ResponseEntity.ok(errorResponse(null, INVALID_REQUEST, "jsonrpc must be '2.0'"));
        }

        JsonNode idNode = req.get("id");
        String method = req.path("method").asText();
        JsonNode params = req.path("params");

        try {
            ObjectNode result = dispatch(method, params);
            return ResponseEntity.ok(successResponse(idNode, result));
        } catch (MethodNotFoundException e) {
            return ResponseEntity.ok(errorResponse(idNode, METHOD_NOT_FOUND, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(errorResponse(idNode, INVALID_PARAMS, e.getMessage()));
        } catch (Exception e) {
            log.error("MCP internal error for method={}", method, e);
            return ResponseEntity.ok(errorResponse(idNode, INTERNAL_ERROR, "Internal error: " + e.getMessage()));
        }
    }

    // ─── dispatch ─────────────────────────────────────────────────────────────

    private ObjectNode dispatch(String method, JsonNode params) {
        return switch (method) {
            case "initialize"   -> handleInitialize(params);
            case "initialized"  -> handleInitialized(params);
            case "tools/list"   -> handleToolsList(params);
            case "tools/call"   -> handleToolsCall(params);
            case "ping"         -> handlePing();
            default             -> throw new MethodNotFoundException("Method not found: " + method);
        };
    }

    // ─── initialize ───────────────────────────────────────────────────────────

    private ObjectNode handleInitialize(JsonNode params) {
        AppProperties.Mcp mcp = appProperties.mcp();
        ObjectNode result = objectMapper.createObjectNode();

        result.put("protocolVersion", mcp.protocolVersion());

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        capabilities.putObject("logging");

        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", mcp.serverName());
        serverInfo.put("version", mcp.serverVersion());

        return result;
    }

    private ObjectNode handleInitialized(JsonNode params) {
        // Client notification — no response body required, return empty result
        return objectMapper.createObjectNode();
    }

    // ─── tools/list ───────────────────────────────────────────────────────────

    private ObjectNode handleToolsList(JsonNode params) {
        // By default expose L3 + read-only L2 tools to the AI agent
        boolean includeAll = params != null && params.path("include_all").asBoolean(false);
        ArrayNode toolsArray = toolRegistry.toToolListJson(includeAll);

        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", toolsArray);
        return result;
    }

    // ─── tools/call ───────────────────────────────────────────────────────────

    private ObjectNode handleToolsCall(JsonNode params) {
        if (params == null || params.isMissingNode()) {
            throw new IllegalArgumentException("params is required for tools/call");
        }
        String name = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        if (arguments.isMissingNode()) {
            arguments = objectMapper.createObjectNode();
        }

        Optional<ToolDefinition> toolOpt = toolRegistry.find(name);
        if (toolOpt.isEmpty()) {
            throw new MethodNotFoundException("Unknown tool: " + name);
        }

        ToolDefinition tool = toolOpt.get();
        Object rawResult;
        try {
            rawResult = tool.handler().handle(arguments);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Tool '{}' threw an error", name, e);
            throw new RuntimeException("Tool execution failed: " + e.getMessage(), e);
        }

        // Convert result to MCP content format
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode contentItem = content.addObject();
        contentItem.put("type", "text");

        if (rawResult instanceof JsonNode) {
            try {
                contentItem.put("text", objectMapper.writeValueAsString(rawResult));
            } catch (Exception e) {
                contentItem.put("text", rawResult.toString());
            }
        } else {
            contentItem.put("text", rawResult != null ? rawResult.toString() : "null");
        }

        result.put("isError", false);
        return result;
    }

    // ─── ping ─────────────────────────────────────────────────────────────────

    private ObjectNode handlePing() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("pong", true);
        return result;
    }

    // ─── GET /mcp/sse ─────────────────────────────────────────────────────────

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse(@RequestParam(required = false) String sessionId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        String key = sessionId != null ? sessionId : "global";
        sseEmitters.put(key, emitter);

        emitter.onCompletion(() -> sseEmitters.remove(key));
        emitter.onTimeout(() -> sseEmitters.remove(key));
        emitter.onError(ex -> sseEmitters.remove(key));

        // Send endpoint event as per MCP SSE spec
        try {
            AppProperties.Mcp mcp = appProperties.mcp();
            ObjectNode endpointEvent = objectMapper.createObjectNode();
            endpointEvent.put("endpoint", "/mcp");
            emitter.send(SseEmitter.event()
                .name("endpoint")
                .data(objectMapper.writeValueAsString(endpointEvent)));
        } catch (IOException e) {
            log.warn("Failed to send SSE endpoint event", e);
        }

        return emitter;
    }

    /**
     * Broadcasts a notification to all connected SSE clients (or a specific session).
     * Called internally when revisions change, sessions update, etc.
     */
    public void broadcast(String eventName, Object data, String sessionId) {
        String key = sessionId != null ? sessionId : "global";
        SseEmitter emitter = sseEmitters.get(key);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                .name(eventName)
                .data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            log.debug("SSE send failed for session {}: {}", key, e.getMessage());
            sseEmitters.remove(key);
        }
    }

    // ─── JSON-RPC 2.0 response builders ──────────────────────────────────────

    private ObjectNode successResponse(JsonNode id, ObjectNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode errorResponse(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    // ─── internal exception ───────────────────────────────────────────────────

    private static class MethodNotFoundException extends RuntimeException {
        MethodNotFoundException(String message) { super(message); }
    }
}
