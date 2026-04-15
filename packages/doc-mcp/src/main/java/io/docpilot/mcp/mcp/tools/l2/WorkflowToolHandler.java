package io.docpilot.mcp.mcp.tools.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.engine.diff.DiffService;
import io.docpilot.mcp.engine.patch.PatchEngine;
import io.docpilot.mcp.engine.projection.HtmlProjectionService;
import io.docpilot.mcp.engine.revision.RevisionService;
import io.docpilot.mcp.mcp.ToolDefinition;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.patch.OperationType;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.patch.PatchOperation;
import io.docpilot.mcp.model.patch.PatchTarget;
import io.docpilot.mcp.model.patch.PatchValidation;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L2 workflow tools: composable, multi-step operations that orchestrate L1 services.
 */
@Component
@RequiredArgsConstructor
public class WorkflowToolHandler {

    private final ObjectMapper objectMapper;
    private final DocumentSessionStore sessionStore;
    private final RevisionStore revisionStore;
    private final PatchEngine patchEngine;
    private final RevisionService revisionService;
    private final DiffService diffService;
    private final HtmlProjectionService htmlProjectionService;
    private final AnchorService anchorService;

    public List<ToolDefinition> definitions() {
        return List.of(
            inspectDocument(),
            rewriteBlock(),
            rewriteSelection(),
            insertAfterBlock(),
            standardizeHeadingHierarchy(),
            restyleSelection(),
            updateTableRegion(),
            prepareRevisionPreview()
        );
    }

    // ─── inspect_document ─────────────────────────────────────────────────────

    private ToolDefinition inspectDocument() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("include_outline").put("type", "boolean").put("default", true);
        props.putObject("include_style_summary").put("type", "boolean").put("default", true);
        schema.putArray("required").add("session_id");

        return new ToolDefinition(
            "inspect_document",
            "Returns a comprehensive overview of the document: metadata (word/paragraph/table counts), section outline, heading hierarchy, and style usage summary.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                ObjectNode out = objectMapper.createObjectNode();

                // Metadata
                ObjectNode meta = out.putObject("metadata");
                meta.put("session_id", s.getSessionId());
                meta.put("doc_id", s.getDocId());
                meta.put("filename", s.getFilename());
                meta.put("word_count", s.getWordCount());
                meta.put("paragraph_count", s.getParagraphCount());
                meta.put("table_count", s.getTableCount());
                meta.put("image_count", s.getImageCount());
                meta.put("section_count", s.getSectionCount());

                boolean includeOutline = params.path("include_outline").asBoolean(true);
                if (includeOutline) {
                    ArrayNode outline = out.putArray("outline");
                    buildOutline(s.getRoot(), outline, 0);
                }

                boolean includeStyles = params.path("include_style_summary").asBoolean(true);
                if (includeStyles) {
                    ObjectNode styleCounts = out.putObject("style_usage");
                    collectStyleUsage(s.getRoot(), styleCounts);
                }

                return out;
            },
            "L2", true
        );
    }

    private void buildOutline(DocumentComponent node, ArrayNode outline, int depth) {
        if (node == null) return;
        if (node.getType() == ComponentType.HEADING) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("block_id", node.getId());
            entry.put("level", node.getLayoutProps() != null ? node.getLayoutProps().getHeadingLevel() : 0);
            entry.put("text", extractText(node));
            entry.put("logical_path", node.getAnchor() != null ? node.getAnchor().getLogicalPath() : "");
            outline.add(entry);
        }
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                buildOutline(child, outline, depth + 1);
            }
        }
    }

    private void collectStyleUsage(DocumentComponent node, ObjectNode counts) {
        if (node == null) return;
        if (node.getStyleRef() != null && node.getStyleRef().getStyleId() != null) {
            String sid = node.getStyleRef().getStyleId();
            counts.put(sid, counts.path(sid).asInt(0) + 1);
        }
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                collectStyleUsage(child, counts);
            }
        }
    }

    // ─── rewrite_block ────────────────────────────────────────────────────────

    private ToolDefinition rewriteBlock() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("block_id").put("type", "string").put("description", "stableId of the block to rewrite");
        props.putObject("new_text").put("type", "string").put("description", "Full replacement text for the block");
        props.putObject("author").put("type", "string");
        props.putObject("summary").put("type", "string");
        schema.putArray("required").add("session_id").add("block_id").add("new_text");

        return new ToolDefinition(
            "rewrite_block",
            "Replaces the entire text content of a single block (paragraph/heading/list item). Stages the change as a PENDING revision for review before committing.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String blockId = params.path("block_id").asText();
                String newText = params.path("new_text").asText();
                String author = params.path("author").asText("agent");
                String summary = params.path("summary").asText("Rewrite block " + blockId);

                DocumentComponent block = findById(s.getRoot(), blockId);
                if (block == null) throw new IllegalArgumentException("Block not found: " + blockId);

                // Build a REPLACE_TEXT_RANGE patch covering the entire block
                ObjectNode value = objectMapper.createObjectNode();
                value.put("text", newText);

                PatchTarget target = PatchTarget.builder()
                    .blockId(blockId)
                    .start(0)
                    .end(Integer.MAX_VALUE)
                    .build();

                PatchOperation op = PatchOperation.builder()
                    .op(OperationType.REPLACE_TEXT_RANGE)
                    .target(target)
                    .value(value)
                    .description("Rewrite block " + blockId)
                    .build();

                Patch patch = buildPatch(s, List.of(op), summary, author, List.of(blockId));
                Revision revision = revisionService.stage(patch, s, author);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("revision_id", revision.getRevisionId());
                out.put("status", revision.getStatus().name());
                out.put("summary", summary);
                return out;
            },
            "L2", false
        );
    }

    // ─── rewrite_selection ────────────────────────────────────────────────────

    private ToolDefinition rewriteSelection() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("block_id").put("type", "string");
        props.putObject("start").put("type", "integer").put("description", "Character offset start (inclusive)");
        props.putObject("end").put("type", "integer").put("description", "Character offset end (exclusive)");
        props.putObject("new_text").put("type", "string");
        props.putObject("author").put("type", "string");
        props.putObject("summary").put("type", "string");
        schema.putArray("required").add("session_id").add("block_id").add("start").add("end").add("new_text");

        return new ToolDefinition(
            "rewrite_selection",
            "Replaces a character range within a block with new text. Stages the change as a PENDING revision.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String blockId = params.path("block_id").asText();
                int start = params.path("start").asInt(0);
                int end = params.path("end").asInt(Integer.MAX_VALUE);
                String newText = params.path("new_text").asText();
                String author = params.path("author").asText("agent");
                String summary = params.path("summary").asText("Rewrite selection in " + blockId);

                ObjectNode value = objectMapper.createObjectNode();
                value.put("text", newText);

                PatchTarget target = PatchTarget.builder()
                    .blockId(blockId)
                    .start(start)
                    .end(end)
                    .build();

                PatchOperation op = PatchOperation.builder()
                    .op(OperationType.REPLACE_TEXT_RANGE)
                    .target(target)
                    .value(value)
                    .description("Rewrite [" + start + ", " + end + "] in block " + blockId)
                    .build();

                Patch patch = buildPatch(s, List.of(op), summary, author, List.of(blockId));
                Revision revision = revisionService.stage(patch, s, author);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("revision_id", revision.getRevisionId());
                out.put("status", revision.getStatus().name());
                return out;
            },
            "L2", false
        );
    }

    // ─── insert_after_block ───────────────────────────────────────────────────

    private ToolDefinition insertAfterBlock() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("after_block_id").put("type", "string");
        props.putObject("text").put("type", "string").put("description", "Text of the new paragraph");
        props.putObject("type").put("type", "string").put("description", "PARAGRAPH | HEADING | LIST_ITEM");
        props.putObject("author").put("type", "string");
        props.putObject("summary").put("type", "string");
        schema.putArray("required").add("session_id").add("after_block_id").add("text");

        return new ToolDefinition(
            "insert_after_block",
            "Inserts a new block (paragraph/heading/list item) immediately after the specified block. Stages as a PENDING revision.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String afterId = params.path("after_block_id").asText();
                String text = params.path("text").asText();
                String typeName = params.path("type").asText("PARAGRAPH");
                String author = params.path("author").asText("agent");
                String summary = params.path("summary").asText("Insert block after " + afterId);

                ObjectNode value = objectMapper.createObjectNode();
                value.put("text", text);
                value.put("type", typeName);
                value.put("after_block_id", afterId);

                PatchTarget target = PatchTarget.builder()
                    .blockId(afterId)
                    .build();

                PatchOperation op = PatchOperation.builder()
                    .op(OperationType.CREATE_BLOCK)
                    .target(target)
                    .value(value)
                    .description("Insert " + typeName + " after " + afterId)
                    .build();

                Patch patch = buildPatch(s, List.of(op), summary, author, List.of(afterId));
                Revision revision = revisionService.stage(patch, s, author);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("revision_id", revision.getRevisionId());
                out.put("status", revision.getStatus().name());
                return out;
            },
            "L2", false
        );
    }

    // ─── standardize_heading_hierarchy ───────────────────────────────────────

    private ToolDefinition standardizeHeadingHierarchy() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("author").put("type", "string");
        schema.putArray("required").add("session_id");

        return new ToolDefinition(
            "standardize_heading_hierarchy",
            "Detects heading level skips (e.g., H1 → H3) and proposes corrections to create a sequential hierarchy. Stages all corrections as a single PENDING revision.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String author = params.path("author").asText("agent");

                List<DocumentComponent> headings = collectHeadings(s.getRoot(), new ArrayList<>());
                List<PatchOperation> ops = new ArrayList<>();
                List<String> affectedIds = new ArrayList<>();

                int expectedLevel = 1;
                for (DocumentComponent h : headings) {
                    int actualLevel = h.getLayoutProps() != null ? h.getLayoutProps().getHeadingLevel() : 1;
                    if (actualLevel > expectedLevel + 1) {
                        // Fix: set to expectedLevel + 1
                        int correctedLevel = expectedLevel + 1;
                        ObjectNode value = objectMapper.createObjectNode();
                        value.put("level", correctedLevel);
                        ops.add(PatchOperation.builder()
                            .op(OperationType.SET_HEADING_LEVEL)
                            .target(PatchTarget.builder().blockId(h.getId()).build())
                            .value(value)
                            .description("Fix heading level " + actualLevel + " → " + correctedLevel)
                            .build());
                        affectedIds.add(h.getId());
                        expectedLevel = correctedLevel;
                    } else {
                        expectedLevel = actualLevel;
                    }
                }

                ObjectNode out = objectMapper.createObjectNode();
                if (ops.isEmpty()) {
                    out.put("already_valid", true);
                    out.put("message", "Heading hierarchy is already correct.");
                    return out;
                }

                Patch patch = buildPatch(s, ops, "Standardize heading hierarchy (" + ops.size() + " fixes)", author, affectedIds);
                Revision revision = revisionService.stage(patch, s, author);
                out.put("revision_id", revision.getRevisionId());
                out.put("fixes_applied", ops.size());
                out.put("status", revision.getStatus().name());
                return out;
            },
            "L2", false
        );
    }

    private List<DocumentComponent> collectHeadings(DocumentComponent node, List<DocumentComponent> acc) {
        if (node == null) return acc;
        if (node.getType() == ComponentType.HEADING) acc.add(node);
        if (node.getChildren() != null) node.getChildren().forEach(c -> collectHeadings(c, acc));
        return acc;
    }

    // ─── restyle_selection ────────────────────────────────────────────────────

    private ToolDefinition restyleSelection() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("block_id").put("type", "string");
        props.putObject("style_id").put("type", "string").put("description", "Target style name/ID to apply");
        props.putObject("run_id").put("type", "string").put("description", "Optional: target a specific text run");
        props.putObject("author").put("type", "string");
        schema.putArray("required").add("session_id").add("block_id").add("style_id");

        return new ToolDefinition(
            "restyle_selection",
            "Applies a named style to an entire block or a specific run within a block. Stages as a PENDING revision.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String blockId = params.path("block_id").asText();
                String styleId = params.path("style_id").asText();
                String runId = params.path("run_id").asText(null);
                String author = params.path("author").asText("agent");

                ObjectNode value = objectMapper.createObjectNode();
                value.put("style_id", styleId);

                PatchTarget.PatchTargetBuilder tb = PatchTarget.builder().blockId(blockId);
                if (runId != null && !runId.isBlank()) tb.runId(runId);
                PatchTarget target = tb.build();

                PatchOperation op = PatchOperation.builder()
                    .op(OperationType.APPLY_STYLE)
                    .target(target)
                    .value(value)
                    .description("Apply style '" + styleId + "' to block " + blockId)
                    .build();

                Patch patch = buildPatch(s, List.of(op), "Restyle block " + blockId, author, List.of(blockId));
                Revision revision = revisionService.stage(patch, s, author);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("revision_id", revision.getRevisionId());
                out.put("status", revision.getStatus().name());
                return out;
            },
            "L2", false
        );
    }

    // ─── update_table_region ─────────────────────────────────────────────────

    private ToolDefinition updateTableRegion() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("table_id").put("type", "string");
        ObjectNode cells = props.putObject("cells");
        cells.put("type", "array");
        ObjectNode cellItem = cells.putObject("items");
        cellItem.put("type", "object");
        ObjectNode cellProps = cellItem.putObject("properties");
        cellProps.putObject("row").put("type", "integer");
        cellProps.putObject("col").put("type", "integer");
        cellProps.putObject("text").put("type", "string");
        props.putObject("author").put("type", "string");
        props.putObject("summary").put("type", "string");
        schema.putArray("required").add("session_id").add("table_id").add("cells");

        return new ToolDefinition(
            "update_table_region",
            "Updates a set of table cells by logical address (row/col). Accepts a list of {row, col, text} entries. Stages as a PENDING revision.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String tableId = params.path("table_id").asText();
                String author = params.path("author").asText("agent");
                String summary = params.path("summary").asText("Update table " + tableId);

                List<PatchOperation> ops = new ArrayList<>();
                List<String> affectedIds = new ArrayList<>();
                affectedIds.add(tableId);

                for (JsonNode cell : params.path("cells")) {
                    int row = cell.path("row").asInt();
                    int col = cell.path("col").asInt();
                    String text = cell.path("text").asText();
                    String cellAddr = "R" + row + "C" + col;

                    ObjectNode value = objectMapper.createObjectNode();
                    value.put("text", text);

                    ops.add(PatchOperation.builder()
                        .op(OperationType.UPDATE_CELL_CONTENT)
                        .target(PatchTarget.builder()
                            .tableId(tableId)
                            .cellLogicalAddress(cellAddr)
                            .build())
                        .value(value)
                        .description("Set cell " + cellAddr + " in table " + tableId)
                        .build());
                }

                Patch patch = buildPatch(s, ops, summary, author, affectedIds);
                Revision revision = revisionService.stage(patch, s, author);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("revision_id", revision.getRevisionId());
                out.put("cells_updated", ops.size());
                out.put("status", revision.getStatus().name());
                return out;
            },
            "L2", false
        );
    }

    // ─── prepare_revision_preview ─────────────────────────────────────────────

    private ToolDefinition prepareRevisionPreview() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        ObjectNode opsNode = props.putObject("operations");
        opsNode.put("type", "array").put("description", "List of PatchOperation objects (JSON)");
        props.putObject("summary").put("type", "string");
        props.putObject("author").put("type", "string");
        schema.putArray("required").add("session_id").add("operations").add("summary");

        return new ToolDefinition(
            "prepare_revision_preview",
            "Bundles a list of raw patch operations into a single PENDING revision without applying it. Returns validation results and the revision ID for review.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String author = params.path("author").asText("agent");
                String summary = params.path("summary").asText("Bulk revision");

                List<PatchOperation> ops = new ArrayList<>();
                for (JsonNode opNode : params.path("operations")) {
                    ops.add(objectMapper.treeToValue(opNode, PatchOperation.class));
                }

                List<String> affectedIds = ops.stream()
                    .map(o -> o.getTarget() != null && o.getTarget().getBlockId() != null
                        ? o.getTarget().getBlockId() : "unknown")
                    .toList();

                Patch patch = buildPatch(s, ops, summary, author, affectedIds);
                Revision revision = revisionService.stage(patch, s, author);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("revision_id", revision.getRevisionId());
                out.put("operation_count", ops.size());
                out.put("status", revision.getStatus().name());

                if (patch.getValidation() != null) {
                    PatchValidation v = patch.getValidation();
                    ObjectNode val = out.putObject("validation");
                    val.put("structure_ok", v.isStructureOk());
                    val.put("style_ok", v.isStyleOk());
                    val.put("scope", v.getScope());
                    ArrayNode errs = val.putArray("errors");
                    if (v.getErrors() != null) v.getErrors().forEach(errs::add);
                    ArrayNode warns = val.putArray("warnings");
                    if (v.getWarnings() != null) v.getWarnings().forEach(warns::add);
                }
                return out;
            },
            "L2", false
        );
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private DocumentSession requireSession(JsonNode params) {
        String sid = params.path("session_id").asText();
        return sessionStore.find(sid).orElseThrow(() -> new IllegalArgumentException("Session not found: " + sid));
    }

    private DocumentComponent findById(DocumentComponent node, String id) {
        if (node == null) return null;
        if (id.equals(node.getId())) return node;
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                DocumentComponent found = findById(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String extractText(DocumentComponent node) {
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

    private Patch buildPatch(DocumentSession s, List<PatchOperation> ops, String summary,
                              String author, List<String> workingSet) {
        String patchId = UUID.randomUUID().toString();
        // Run dry-run via PatchEngine to populate validation
        Patch draft = Patch.builder()
            .patchId(patchId)
            .sessionId(s.getSessionId())
            .baseRevisionId(s.getCurrentRevisionId())
            .operations(ops)
            .summary(summary)
            .workingSet(workingSet)
            .build();
        PatchValidation validation = patchEngine.dryRun(draft, s);
        return Patch.builder()
            .patchId(patchId)
            .sessionId(s.getSessionId())
            .baseRevisionId(s.getCurrentRevisionId())
            .operations(ops)
            .summary(summary)
            .validation(validation)
            .workingSet(workingSet)
            .author(author)
            .createdAt(java.time.Instant.now())
            .build();
    }
}
