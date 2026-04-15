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
import io.docpilot.mcp.personalization.DocumentTextSupport;
import io.docpilot.mcp.personalization.SemanticSearchMatch;
import io.docpilot.mcp.personalization.SemanticSearchService;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private final SemanticSearchService semanticSearchService;

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
                out.put("semantic_search_enabled", semanticSearchService.isEnabled());
                out.put("retrieval_provider", semanticSearchService.providerName());

                // Heading outline for navigation
                ArrayNode headings = out.putArray("headings");
                collectHeadingsToArray(s.getRoot(), headings);

                ArrayNode snippets = out.putArray("relevant_snippets");
                Set<String> seenBlockIds = new LinkedHashSet<>();
                appendSemanticSnippets(s, question, snippets, seenBlockIds, 8);

                String[] keywords = question.toLowerCase(Locale.ROOT).split("\\s+");
                collectSnippets(s.getRoot(), keywords, snippets, seenBlockIds, new int[]{snippets.size()});

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
            e.put("text", DocumentTextSupport.extractText(node));
            out.add(e);
        }
        if (node.getChildren() != null) node.getChildren().forEach(c -> collectHeadingsToArray(c, out));
    }

    private void collectSnippets(DocumentComponent node, String[] keywords, ArrayNode out, Set<String> seenBlockIds, int[] count) {
        if (count[0] >= 20 || node == null) return;
        String text = DocumentTextSupport.extractText(node);
        String lowerText = text.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (!kw.isBlank() && lowerText.contains(kw) && seenBlockIds.add(node.getId())) {
                ObjectNode snippet = objectMapper.createObjectNode();
                snippet.put("block_id", node.getId());
                snippet.put("type", node.getType().name());
                snippet.put("text", text);
                if (node.getAnchor() != null && node.getAnchor().getLogicalPath() != null) {
                    snippet.put("logical_path", node.getAnchor().getLogicalPath());
                }
                snippet.put("source", "keyword");
                out.add(snippet);
                count[0]++;
                break;
            }
        }
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                collectSnippets(child, keywords, out, seenBlockIds, count);
            }
        }
    }

    private void appendSemanticSnippets(
        DocumentSession session,
        String query,
        ArrayNode out,
        Set<String> seenBlockIds,
        int limit
    ) {
        for (SemanticSearchMatch match : semanticSearchService.search(session, query, limit)) {
            if (!seenBlockIds.add(match.blockId())) {
                continue;
            }
            ObjectNode snippet = objectMapper.createObjectNode();
            snippet.put("block_id", match.blockId());
            snippet.put("type", match.type());
            snippet.put("text", match.text());
            snippet.put("logical_path", match.logicalPath());
            snippet.put("heading_path", match.headingPath());
            snippet.put("score", match.score());
            snippet.put("source", "semantic");
            out.add(snippet);
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
                String rawQuery = params.path("query").asText();
                String query = rawQuery.toLowerCase(Locale.ROOT);

                ArrayNode results = objectMapper.createArrayNode();
                Set<String> seenBlockIds = new LinkedHashSet<>();
                appendSemanticContextMatches(s, rawQuery, results, seenBlockIds, 12);
                searchContext(s.getRoot(), query, results, seenBlockIds, new int[]{results.size()});

                ObjectNode out = objectMapper.createObjectNode();
                out.put("query", rawQuery);
                out.put("retrieval_provider", semanticSearchService.providerName());
                out.put("match_count", results.size());
                out.set("matches", results);
                return out;
            },
            "L3", true
        );
    }

    private void searchContext(DocumentComponent node, String query, ArrayNode results, Set<String> seenBlockIds, int[] count) {
        if (count[0] >= 30 || node == null) return;
        String text = DocumentTextSupport.extractText(node);
        String lowerText = text.toLowerCase(Locale.ROOT);
        if (lowerText.contains(query) && seenBlockIds.add(node.getId())) {
            int offset = lowerText.indexOf(query);
            ObjectNode match = objectMapper.createObjectNode();
            match.put("block_id", node.getId());
            match.put("type", node.getType().name());
            match.put("text", text);
            match.put("match_offset", offset);
            match.put("match_length", query.length());
            if (node.getAnchor() != null) {
                match.put("logical_path", node.getAnchor().getLogicalPath());
            }
            match.put("source", "keyword");
            results.add(match);
            count[0]++;
        }
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                searchContext(child, query, results, seenBlockIds, count);
            }
        }
    }

    private void appendSemanticContextMatches(
        DocumentSession session,
        String query,
        ArrayNode results,
        Set<String> seenBlockIds,
        int limit
    ) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        for (SemanticSearchMatch match : semanticSearchService.search(session, query, limit)) {
            if (!seenBlockIds.add(match.blockId())) {
                continue;
            }
            String lowerText = match.text().toLowerCase(Locale.ROOT);
            int offset = lowerText.indexOf(lowerQuery);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("block_id", match.blockId());
            result.put("type", match.type());
            result.put("text", match.text());
            result.put("match_offset", offset);
            result.put("match_length", offset >= 0 ? query.length() : 0);
            result.put("logical_path", match.logicalPath());
            result.put("heading_path", match.headingPath());
            result.put("score", match.score());
            result.put("source", "semantic");
            results.add(result);
        }
    }

    // ─── propose_document_edit ────────────────────────────────────────────────

    private ToolDefinition proposeDocumentEdit() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("base_revision_id").put("type", "string").put("description", "Revision the author was looking at when composing the edit. Defaults to the current session revision.");
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
                String baseRevisionId = params.path("base_revision_id").asText(null);
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
                    .baseRevisionId(baseRevisionId != null && !baseRevisionId.isBlank() ? baseRevisionId : s.getCurrentRevisionId())
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
                    .baseRevisionId(draft.getBaseRevisionId())
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
                .start(targetNode.hasNonNull("start") ? targetNode.get("start").asInt() : null)
                .end(targetNode.hasNonNull("end") ? targetNode.get("end").asInt() : null)
                .tableId(targetNode.path("table_id").asText(null))
                .rowId(targetNode.path("row_id").asText(null))
                .cellId(targetNode.path("cell_id").asText(targetNode.path("cellId").asText(null)))
                .cellLogicalAddress(targetNode.path("cell_logical_address").asText(targetNode.path("cellLogicalAddress").asText(null)))
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

                var baseTree = (baseId == null || baseId.isBlank())
                    ? revisionStore.findInitialSnapshot(s.getSessionId())
                    : revisionStore.findSnapshot(s.getSessionId(), baseId);
                var targetTree = targetId.equals(s.getCurrentRevisionId())
                    ? java.util.Optional.of(s.getRoot())
                    : revisionStore.findSnapshot(s.getSessionId(), targetId);

                if (baseTree.isEmpty()) {
                    throw new IllegalArgumentException("Base revision snapshot not found: " + baseId);
                }
                if (targetTree.isEmpty()) {
                    throw new IllegalArgumentException("Target revision snapshot not found: " + targetId);
                }

                DocumentDiff diff = diffService.compute(baseTree.get(), targetTree.get(), baseId, targetId, s.getSessionId());

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
            "Signals that the current session is ready to export. Returns export metadata and a download URL path. The actual DOCX bytes are available via POST /api/sessions/{id}/export-docx.",
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
