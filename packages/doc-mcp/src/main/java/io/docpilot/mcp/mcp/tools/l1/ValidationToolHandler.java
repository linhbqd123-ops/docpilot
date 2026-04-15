package io.docpilot.mcp.mcp.tools.l1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.engine.validation.ValidationService;
import io.docpilot.mcp.mcp.ToolDefinition;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * L1 validation tools: validate_document_structure, validate_styles, check_breaking_change.
 */
@Component
@RequiredArgsConstructor
public class ValidationToolHandler {

    private final ObjectMapper objectMapper;
    private final DocumentSessionStore sessionStore;
    private final RevisionStore revisionStore;
    private final ValidationService validationService;
    private final io.docpilot.mcp.storage.RegistryStore registryStore;

    public List<ToolDefinition> definitions() {
        return List.of(
            new ToolDefinition(
                "validate_document_structure",
                "Validates the structural integrity of a document session. Checks component tree consistency, heading hierarchy, parent-child relationships, and anchor completeness.",
                sessionIdSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    ValidationService.ValidationResult result = validationService.validateStructure(s);
                    ObjectNode out = objectMapper.createObjectNode();
                    out.put("ok", result.ok());
                    var errors = out.putArray("errors");
                    result.errors().forEach(errors::add);
                    var warnings = out.putArray("warnings");
                    result.warnings().forEach(warnings::add);
                    return out;
                },
                "L1", true
            ),
            new ToolDefinition(
                "validate_styles",
                "Validates that all styleRefs in the document reference known styles. Returns a list of warnings for unknown styles.",
                sessionIdSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    // Collect known style IDs from registry
                    Set<String> knownIds = registryStore.findRegistry(s.getDocId())
                        .map(reg -> reg.getStyles() != null ? reg.getStyles().keySet() : Set.<String>of())
                        .orElse(Set.of());
                    ValidationService.ValidationResult result = validationService.validateStyles(s, knownIds);
                    ObjectNode out = objectMapper.createObjectNode();
                    out.put("ok", result.ok());
                    var warnings = out.putArray("warnings");
                    result.warnings().forEach(warnings::add);
                    return out;
                },
                "L1", true
            ),
            new ToolDefinition(
                "check_breaking_change",
                "Estimates the impact of a change that affects the given list of block stableIds. Returns warnings if headings will be modified or if the change affects a large portion of the document.",
                breakingChangeSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    var blockIdsNode = params.path("affected_block_ids");
                    List<String> blockIds = new java.util.ArrayList<>();
                    blockIdsNode.forEach(n -> blockIds.add(n.asText()));
                    ValidationService.ValidationResult result = validationService.checkBreakingChange(s, blockIds);
                    ObjectNode out = objectMapper.createObjectNode();
                    out.put("ok", result.ok());
                    var warnings = out.putArray("warnings");
                    result.warnings().forEach(warnings::add);
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

    private ObjectNode sessionIdSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("session_id").put("type", "string");
        root.putArray("required").add("session_id");
        return root;
    }

    private ObjectNode breakingChangeSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("affected_block_ids").put("type", "array")
            .put("description", "List of stableIds of blocks that will be affected");
        root.putArray("required").add("session_id").add("affected_block_ids");
        return root;
    }
}
