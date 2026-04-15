package io.docpilot.mcp.mcp.tools.l1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.engine.diff.DiffService;
import io.docpilot.mcp.engine.patch.PatchEngine;
import io.docpilot.mcp.engine.revision.RevisionService;
import io.docpilot.mcp.mcp.ToolDefinition;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.patch.PatchValidation;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * L1 change management tools: create_patch, dry_run_patch, apply_patch, rollback_patch,
 * compute_diff, create_version_snapshot.
 */
@Component
@RequiredArgsConstructor
public class ChangeToolHandler {

    private final ObjectMapper objectMapper;
    private final DocumentSessionStore sessionStore;
    private final RevisionStore revisionStore;
    private final PatchEngine patchEngine;
    private final RevisionService revisionService;
    private final DiffService diffService;

    public List<ToolDefinition> definitions() {
        return List.of(
            new ToolDefinition(
                "dry_run_patch",
                "Validates a patch against the current session state without applying it. Returns validation result with errors, warnings, affected blocks, and scope estimate.",
                patchSchema(),
                params -> {
                    DocumentSession s = requireSession(params);
                    Patch patch = objectMapper.treeToValue(params.path("patch"), Patch.class);
                    PatchValidation validation = patchEngine.dryRun(patch, s);
                    return objectMapper.valueToTree(validation);
                },
                "L1", false
            ),
            new ToolDefinition(
                "create_version_snapshot",
                "Creates a named snapshot of the current session state that can be used as a rollback point.",
                sessionIdSchema(),
                params -> {
                    String sessionId = params.path("session_id").asText();
                    DocumentSession s = requireSession(params);
                    String snapshotId = "snap_" + UUID.randomUUID().toString().substring(0, 8);
                    revisionStore.saveSnapshot(sessionId, snapshotId, s.getRoot());
                    ObjectNode out = objectMapper.createObjectNode();
                    out.put("snapshotId", snapshotId);
                    out.put("sessionId",  sessionId);
                    out.put("createdAt",  Instant.now().toString());
                    out.put("currentRevisionId", s.getCurrentRevisionId());
                    return out;
                },
                "L1", false
            )
        );
    }

    private DocumentSession requireSession(com.fasterxml.jackson.databind.JsonNode params) {
        String sid = params.path("session_id").asText();
        return sessionStore.find(sid).orElseThrow(() -> new IllegalArgumentException("Session not found: " + sid));
    }

    private ObjectNode patchSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("patch").put("type", "object").put("description", "The Patch object to validate");
        root.putArray("required").add("session_id").add("patch");
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
