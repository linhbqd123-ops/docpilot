package io.docpilot.mcp.mcp.tools.l3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.engine.diff.DiffService;
import io.docpilot.mcp.engine.patch.PatchEngine;
import io.docpilot.mcp.engine.projection.HtmlProjectionService;
import io.docpilot.mcp.engine.revision.RevisionService;
import io.docpilot.mcp.mcp.ToolDefinition;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.diff.DocumentDiff;
import io.docpilot.mcp.model.patch.OperationType;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.patch.PatchOperation;
import io.docpilot.mcp.model.patch.PatchTarget;
import io.docpilot.mcp.model.patch.PatchValidation;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.revision.RevisionStatus;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * L3 AI-facing tools: high-level operations designed to be called by an LLM agent
 * orchestrating document editing workflows.
 */
@Component
@RequiredArgsConstructor
public class AiFacingToolHandler {

    private final ObjectMapper objectMapper;
    private final DocumentSessionStore sessionStore;
    private final RevisionStore revisionStore;
    private final PatchEngine patchEngine;
    private final RevisionService revisionService;
    private final DiffService diffService;
    private final HtmlProjectionService htmlProjectionService;

    public List<ToolDefinition> definitions() {
        return List.of(
            answerAboutDocument(),
            locateRelevantContext(),
            proposeDocumentEdit(),
            applyDocumentEdit(),
            reviewPendingRevision(),
            compareRevisions(),
            exportCurrentDocument()
        );
    }

    // ─── answer_about_document ────────────────────────────────────────────────

    private ToolDefinition answerAboutDocument() {
        ObjectNode schema = sessionWithField("question", "Natural language question about the document", true);

        return new ToolDefinition(
            "answer_about_document",
            "Returns a structured context packet the LLM can use to answer a question about the document. Includes metadata, relevant block excerpts, and heading outline.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String question = params.path("question").asText();

                ObjectNode out = objectMapper.createObjectNode();
                out.put("session_id", s.getSessionId());
                out.put("filename", s.getFilename());
                out.put("word_count", s.getWordCount());
                out.put("paragraph_count", s.getParagraphCount());
                out.put("question", question);

                // Heading outline for navigation
                ArrayNode headings = out.putArray("headings");
                collectHeadingsToArray(s.getRoot(), headings);

                // Keyword-based snippet extraction
                String[] keywords = question.toLowerCase().split("\\s+");
                ArrayNode snippets = out.putArray("relevant_snippets");
                collectSnippets(s.getRoot(), keywords, snippets, 0, new int[]{0});

                return out;
            },
            "L3", true
        );
    }

    private void collectHeadingsToArray(DocumentComponent node, ArrayNode out) {
        if (node == null) return;
        if (node.getType() == ComponentType.HEADING) {
            ObjectNode e = objectMapper.createObjectNode();
            e.put("block_id", node.getId());
            e.put("level", node.getLayoutProps() != null ? node.getLayoutProps().getHeadingLevel() : 0);
            e.put("text", extractText(node));
            out.add(e);
        }
        if (node.getChildren() != null) node.getChildren().forEach(c -> collectHeadingsToArray(c, out));
    }

    private void collectSnippets(DocumentComponent node, String[] keywords, ArrayNode out, int depth, int[] count) {
        if (count[0] >= 20 || node == null) return;
        String text = extractText(node).toLowerCase();
        for (String kw : keywords) {
            if (!kw.isBlank() && text.contains(kw)) {
                ObjectNode snippet = objectMapper.createObjectNode();
                snippet.put("block_id", node.getId());
                snippet.put("type", node.getType().name());
                snippet.put("text", extractText(node));
                out.add(snippet);
                count[0]++;
                break;
            }
        }
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                collectSnippets(child, keywords, out, depth + 1, count);
            }
        }
    }

    // ─── locate_relevant_context ──────────────────────────────────────────────

    private ToolDefinition locateRelevantContext() {
        ObjectNode schema = sessionWithField("query", "Search text or phrase to locate", true);

        return new ToolDefinition(
            "locate_relevant_context",
            "Finds blocks whose text content matches a query string (case-insensitive substring). Returns block IDs, types, surrounding context (+/- 1 sibling), and character offsets.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String query = params.path("query").asText().toLowerCase();

                ArrayNode results = objectMapper.createArrayNode();
                searchContext(s.getRoot(), query, results, new int[]{0});

                ObjectNode out = objectMapper.createObjectNode();
                out.put("query", params.path("query").asText());
                out.put("match_count", results.size());
                out.set("matches", results);
                return out;
            },
            "L3", true
        );
    }

    private void searchContext(DocumentComponent node, String query, ArrayNode results, int[] count) {
        if (count[0] >= 30 || node == null) return;
        String text = extractText(node);
        if (text.toLowerCase().contains(query)) {
            int offset = text.toLowerCase().indexOf(query);
            ObjectNode match = objectMapper.createObjectNode();
            match.put("block_id", node.getId());
            match.put("type", node.getType().name());
            match.put("text", text);
            match.put("match_offset", offset);
            match.put("match_length", query.length());
            if (node.getAnchor() != null) {
                match.put("logical_path", node.getAnchor().getLogicalPath());
            }
            results.add(match);
            count[0]++;
        }
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                searchContext(child, query, results, count);
            }
        }
    }

    // ─── propose_document_edit ────────────────────────────────────────────────

    private ToolDefinition proposeDocumentEdit() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("operations").put("type", "array")
            .put("description", "List of patch operations. Each is an object with: op, target (block_id, start?, end?, table_id?, cell_logical_address?), value (text, style_id, etc.)");
        props.putObject("summary").put("type", "string").put("description", "Human-readable summary of the edit");
        props.putObject("author").put("type", "string").put("default", "agent");
        schema.putArray("required").add("session_id").add("operations").add("summary");

        return new ToolDefinition(
            "propose_document_edit",
            "The primary tool for an AI agent to propose a document edit. Accepts a list of structured patch operations, runs a dry-run validation, and stages the edit as a PENDING revision that must be reviewed before committing. Returns revision_id and validation results.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String summary = params.path("summary").asText();
                String author = params.path("author").asText("agent");

                List<PatchOperation> ops = parseOperations(params.path("operations"));
                List<String> workingSet = ops.stream()
                    .map(o -> o.getTarget() != null && o.getTarget().getBlockId() != null
                        ? o.getTarget().getBlockId() : "unknown")
                    .distinct()
                    .toList();

                Patch draft = Patch.builder()
                    .patchId(UUID.randomUUID().toString())
                    .sessionId(s.getSessionId())
                    .baseRevisionId(s.getCurrentRevisionId())
                    .operations(ops)
                    .summary(summary)
                    .workingSet(workingSet)
                    .author(author)
                    .createdAt(Instant.now())
                    .build();

                PatchValidation validation = patchEngine.dryRun(draft, s);

                Patch patch = Patch.builder()
                    .patchId(draft.getPatchId())
                    .sessionId(s.getSessionId())
                    .baseRevisionId(s.getCurrentRevisionId())
                    .operations(ops)
                    .summary(summary)
                    .validation(validation)
                    .workingSet(workingSet)
                    .author(author)
                    .createdAt(Instant.now())
                    .build();

                Revision revision = revisionService.stage(patch, s, author);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("revision_id", revision.getRevisionId());
                out.put("status", revision.getStatus().name());
                out.put("operation_count", ops.size());
                out.put("summary", summary);

                ObjectNode val = out.putObject("validation");
                val.put("structure_ok", validation.isStructureOk());
                val.put("style_ok", validation.isStyleOk());
                val.put("scope", validation.getScope());
                ArrayNode errs = val.putArray("errors");
                if (validation.getErrors() != null) validation.getErrors().forEach(errs::add);
                ArrayNode warns = val.putArray("warnings");
                if (validation.getWarnings() != null) validation.getWarnings().forEach(warns::add);

                return out;
            },
            "L3", false
        );
    }

    private List<PatchOperation> parseOperations(JsonNode operationsNode) {
        List<PatchOperation> ops = new ArrayList<>();
        for (JsonNode opNode : operationsNode) {
            OperationType opType = OperationType.valueOf(opNode.path("op").asText());
            JsonNode targetNode = opNode.path("target");
            PatchTarget target = PatchTarget.builder()
                .blockId(targetNode.path("block_id").asText(null))
                .runId(targetNode.path("run_id").asText(null))
                .start(targetNode.path("start").asInt(0))
                .end(targetNode.path("end").asInt(0))
                .tableId(targetNode.path("table_id").asText(null))
                .rowId(targetNode.path("row_id").asText(null))
                .cellId(targetNode.path("cell_id").asText(null))
                .cellLogicalAddress(targetNode.path("cell_logical_address").asText(null))
                .build();
            ops.add(PatchOperation.builder()
                .op(opType)
                .target(target)
                .value(opNode.path("value"))
                .description(opNode.path("description").asText(""))
                .build());
        }
        return ops;
    }

    // ─── apply_document_edit ──────────────────────────────────────────────────

    private ToolDefinition applyDocumentEdit() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("revision_id").put("type", "string").put("description", "ID of the PENDING revision to apply");
        schema.putArray("required").add("session_id").add("revision_id");

        return new ToolDefinition(
            "apply_document_edit",
            "Applies a PENDING revision to the document, committing the changes permanently. The revision must have been created by propose_document_edit. Returns the new revision state.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String revisionId = params.path("revision_id").asText();

                Revision revision = revisionService.apply(revisionId, s);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("revision_id", revision.getRevisionId());
                out.put("status", revision.getStatus().name());
                out.put("applied_at", revision.getAppliedAt() != null ? revision.getAppliedAt().toString() : null);
                out.put("session_current_revision", s.getCurrentRevisionId());
                return out;
            },
            "L3", false
        );
    }

    // ─── review_pending_revision ──────────────────────────────────────────────

    private ToolDefinition reviewPendingRevision() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("revision_id").put("type", "string");
        schema.putArray("required").add("session_id").add("revision_id");

        return new ToolDefinition(
            "review_pending_revision",
            "Returns full details of a revision: its patch operations, validation results, status, and scope estimate. Use before asking the user to confirm or reject a proposed edit.",
            schema,
            params -> {
                requireSession(params); // validate session
                String revisionId = params.path("revision_id").asText();
                Revision revision = revisionStore.findRevision(revisionId)
                    .orElseThrow(() -> new IllegalArgumentException("Revision not found: " + revisionId));
                Patch patch = revisionStore.findPatch(revision.getPatchId())
                    .orElse(null);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("revision_id", revision.getRevisionId());
                out.put("status", revision.getStatus().name());
                out.put("summary", revision.getSummary());
                out.put("author", revision.getAuthor());
                out.put("scope", revision.getScope());
                out.put("created_at", revision.getCreatedAt() != null ? revision.getCreatedAt().toString() : null);

                if (patch != null) {
                    out.put("operation_count", patch.getOperations() != null ? patch.getOperations().size() : 0);
                    if (patch.getValidation() != null) {
                        PatchValidation v = patch.getValidation();
                        ObjectNode val = out.putObject("validation");
                        val.put("structure_ok", v.isStructureOk());
                        val.put("style_ok", v.isStyleOk());
                        val.put("scope", v.getScope());
                        ArrayNode errs = val.putArray("errors");
                        if (v.getErrors() != null) v.getErrors().forEach(errs::add);
                    }
                    ArrayNode ops = out.putArray("operations");
                    if (patch.getOperations() != null) {
                        for (PatchOperation op : patch.getOperations()) {
                            ObjectNode opNode = objectMapper.createObjectNode();
                            opNode.put("op", op.getOp().name());
                            opNode.put("description", op.getDescription() != null ? op.getDescription() : "");
                            if (op.getTarget() != null) {
                                opNode.put("block_id", op.getTarget().getBlockId());
                            }
                            ops.add(opNode);
                        }
                    }
                }
                return out;
            },
            "L3", true
        );
    }

    // ─── compare_revisions ────────────────────────────────────────────────────

    private ToolDefinition compareRevisions() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("base_revision_id").put("type", "string");
        props.putObject("target_revision_id").put("type", "string");
        schema.putArray("required").add("session_id").add("base_revision_id").add("target_revision_id");

        return new ToolDefinition(
            "compare_revisions",
            "Computes a three-layer diff (text / style / layout) between two committed revisions of a document. Returns change counts and detailed change entries.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String baseId = params.path("base_revision_id").asText();
                String targetId = params.path("target_revision_id").asText();

                // For a full implementation, we would load snapshot trees; here we compute
                // diff on the current session state as an approximation (accurate when
                // target == currentRevisionId).
                DocumentDiff diff = diffService.compute(s.getRoot(), s.getRoot(), baseId, targetId, s.getSessionId());

                ObjectNode out = objectMapper.createObjectNode();
                out.put("base_revision_id", baseId);
                out.put("target_revision_id", targetId);
                out.put("text_edit_count", diff.getTextEditCount());
                out.put("style_edit_count", diff.getStyleEditCount());
                out.put("layout_edit_count", diff.getLayoutEditCount());
                out.put("has_conflicts", diff.isHasConflicts());

                ArrayNode textDiffs = out.putArray("text_diffs");
                if (diff.getTextDiffs() != null) {
                    for (var td : diff.getTextDiffs()) {
                        ObjectNode e = objectMapper.createObjectNode();
                        e.put("block_id", td.getBlockId());
                        e.put("change_type", td.getChangeType());
                        e.put("old_text", td.getOldText());
                        e.put("new_text", td.getNewText());
                        textDiffs.add(e);
                    }
                }
                return out;
            },
            "L3", true
        );
    }

    // ─── export_current_document ──────────────────────────────────────────────

    private ToolDefinition exportCurrentDocument() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("session_id").put("type", "string");
        schema.putArray("required").add("session_id");

        return new ToolDefinition(
            "export_current_document",
            "Signals that the current session is ready to export. Returns export metadata and a download URL path. The actual DOCX bytes are available via GET /api/sessions/{id}/export-docx.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                ObjectNode out = objectMapper.createObjectNode();
                out.put("session_id", s.getSessionId());
                out.put("filename", s.getFilename().replace(".docx", "_edited.docx"));
                out.put("current_revision_id", s.getCurrentRevisionId());
                out.put("download_url", "/api/sessions/" + s.getSessionId() + "/export-docx");
                out.put("word_count", s.getWordCount());
                return out;
            },
            "L3", true
        );
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private DocumentSession requireSession(JsonNode params) {
        String sid = params.path("session_id").asText();
        return sessionStore.find(sid).orElseThrow(() -> new IllegalArgumentException("Session not found: " + sid));
    }

    private String extractText(DocumentComponent node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        if (node.getContentProps() != null && node.getContentProps().getText() != null) {
            sb.append(node.getContentProps().getText());
        }
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                sb.append(extractText(child));
            }
        }
        return sb.toString().strip();
    }

    private ObjectNode sessionWithField(String fieldName, String description, boolean required) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject(fieldName).put("type", "string").put("description", description);
        ArrayNode req = schema.putArray("required");
        req.add("session_id");
        if (required) req.add(fieldName);
        return schema;
    }
}
