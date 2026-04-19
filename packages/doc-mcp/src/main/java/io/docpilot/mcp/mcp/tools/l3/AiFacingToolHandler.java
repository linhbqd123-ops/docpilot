package io.docpilot.mcp.mcp.tools.l3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.engine.diff.DiffService;
import io.docpilot.mcp.engine.fidelity.FidelityHtmlService;
import io.docpilot.mcp.engine.line.LineExtractorService;
import io.docpilot.mcp.engine.patch.PatchEngine;
import io.docpilot.mcp.engine.projection.HtmlProjectionService;
import io.docpilot.mcp.engine.revision.RevisionService;
import io.docpilot.mcp.mcp.ToolDefinition;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.document.DocumentLine;
import io.docpilot.mcp.model.document.LayoutProps;
import io.docpilot.mcp.model.document.StyleRef;
import io.docpilot.mcp.model.legacy.StyleEntry;
import io.docpilot.mcp.storage.RegistryStore;
import io.docpilot.mcp.model.diff.DocumentDiff;
import io.docpilot.mcp.model.patch.OperationType;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.patch.PatchOperation;
import io.docpilot.mcp.model.patch.PatchTarget;
import io.docpilot.mcp.model.patch.PatchValidation;
import io.docpilot.mcp.model.revision.PendingRevisionPreview;
import io.docpilot.mcp.model.revision.Revision;
import io.docpilot.mcp.model.revision.RevisionStatus;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.personalization.DocumentTextSupport;
import io.docpilot.mcp.personalization.SemanticSearchMatch;
import io.docpilot.mcp.personalization.SemanticSearchService;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.store.RevisionStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * L3 AI-facing tools: high-level operations designed to be called by an LLM agent
 * orchestrating document editing workflows.
 */
@Slf4j
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
    private final AnchorService anchorService;
    private final RegistryStore registryStore;
    private final LineExtractorService lineExtractorService;

    public List<ToolDefinition> definitions() {
        return List.of(
            answerAboutDocument(),
            locateRelevantContext(),
            getSelectionContext(),
            getDocumentOutline(),
            getStyleContext(),
            searchText(),
            searchTextWithHtml(),
            getDocumentLines(),
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
            // Skip very short tokens (stop words) to avoid spurious matches
            if (kw.length() < 3) continue;
            if (lowerText.contains(kw) && seenBlockIds.add(node.getId())) {
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

                log.info("locate_relevant_context: query='{}' (lowercased: '{}')", rawQuery, query);
                if (s.getRoot() == null) {
                    log.warn("Document root is null - cannot search");
                }

                ArrayNode results = objectMapper.createArrayNode();
                Set<String> seenBlockIds = new LinkedHashSet<>();
                
                int[] visitedCount = new int[]{0};
                appendSemanticContextMatches(s, rawQuery, results, seenBlockIds, 12);
                searchContextWithLogging(s.getRoot(), query, results, seenBlockIds, new int[]{results.size()}, visitedCount);
                
                log.info("locate_relevant_context completed: query='{}', matched={}, visited_nodes={}", 
                    rawQuery, results.size(), visitedCount[0]);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("query", rawQuery);
                out.put("retrieval_provider", semanticSearchService.providerName());
                out.put("match_count", results.size());
                out.put("visited_nodes", visitedCount[0]);
                out.set("matches", results);
                return out;
            },
            "L3", true
        );
    }

    /**
     * Splits a raw query string into matchable tokens.
     * Tokens are split on any non-alphanumeric character (spaces, slashes, dashes, etc.).
     * Tokens shorter than 3 characters are excluded to avoid stop-word noise.
     */
    private List<String> tokenizeQuery(String lowercasedQuery) {
        List<String> tokens = new ArrayList<>();
        for (String part : lowercasedQuery.split("[\\s/,]+")) {
            if (part.length() >= 3) {
                tokens.add(part);
            }
        }
        // If all tokens were too short, fall back to the full query as-is
        if (tokens.isEmpty() && !lowercasedQuery.isBlank()) {
            tokens.add(lowercasedQuery.strip());
        }
        return tokens;
    }

    /** Returns the first token found in lowerText, or null if none matches. */
    private String firstMatchingToken(String lowerText, List<String> tokens) {
        for (String token : tokens) {
            if (lowerText.contains(token)) return token;
        }
        return null;
    }

    private void searchContextWithLogging(DocumentComponent node, String query, ArrayNode results, Set<String> seenBlockIds, int[] count, int[] visitedCount) {
        if (count[0] >= 30 || node == null) return;
        
        visitedCount[0]++;
        String text = DocumentTextSupport.extractText(node);
        String lowerText = text.toLowerCase(Locale.ROOT);
        
        List<String> tokens = tokenizeQuery(query);
        String matchedToken = firstMatchingToken(lowerText, tokens);

        if (matchedToken != null && seenBlockIds.add(node.getId())) {
            int offset = lowerText.indexOf(matchedToken);
            ObjectNode match = objectMapper.createObjectNode();
            match.put("block_id", node.getId());
            match.put("type", node.getType().name());
            match.put("text", text);
            match.put("match_offset", offset);
            match.put("match_length", matchedToken.length());
            if (node.getAnchor() != null) {
                match.put("logical_path", node.getAnchor().getLogicalPath());
            }
            match.put("source", "keyword");
            results.add(match);
            count[0]++;
            log.debug("Found match in node {}: text='{}' (query='{}', matched token='{}')", node.getId(), text, query, matchedToken);
        } else if (text.length() < 200 && visitedCount[0] <= 50) {
            // Log first 50 nodes for diagnosis
            log.debug("Node {} (type={}): text='{}' (no match for query='{}')", node.getId(), node.getType(), text, query);
        }
        
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                searchContextWithLogging(child, query, results, seenBlockIds, count, visitedCount);
            }
        }
    }

    private void searchContext(DocumentComponent node, String query, ArrayNode results, Set<String> seenBlockIds, int[] count) {
        if (count[0] >= 30 || node == null) return;
        String text = DocumentTextSupport.extractText(node);
        String lowerText = text.toLowerCase(Locale.ROOT);
        List<String> tokens = tokenizeQuery(query);
        String matchedToken = firstMatchingToken(lowerText, tokens);
        if (matchedToken != null && seenBlockIds.add(node.getId())) {
            int offset = lowerText.indexOf(matchedToken);
            ObjectNode match = objectMapper.createObjectNode();
            match.put("block_id", node.getId());
            match.put("type", node.getType().name());
            match.put("text", text);
            match.put("match_offset", offset);
            match.put("match_length", matchedToken.length());
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

    // ─── get_selection_context ────────────────────────────────────────────────

    private ToolDefinition getSelectionContext() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("block_id").put("type", "string")
            .put("description", "The target block (selection anchor). Use the block's stableId.");
        props.putObject("window_size").put("type", "integer")
            .put("description", "Number of sibling blocks to include before and after the target block (default 3).");
        schema.putArray("required").add("session_id").add("block_id");

        return new ToolDefinition(
            "get_selection_context",
            "Returns rich context around a selected block: the surrounding N sibling blocks as plain text and as annotated HTML, plus the ancestor heading/section hierarchy. Use this to give the model precise context before generating an edit.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String blockId = params.path("block_id").asText();
                int windowSize = params.path("window_size").asInt(3);

                Map<String, DocumentComponent> index = anchorService.buildIndex(s.getRoot());
                DocumentComponent target = index.get(blockId);
                if (target == null) throw new IllegalArgumentException("Block not found: " + blockId);

                // Collect sibling window
                List<DocumentComponent> windowBlocks = collectSiblingWindow(target, index, windowSize);

                // Build plain_text: concatenate text of all window blocks
                StringBuilder plainText = new StringBuilder();
                for (DocumentComponent block : windowBlocks) {
                    String t = DocumentTextSupport.extractText(block);
                    if (!t.isBlank()) {
                        if (!plainText.isEmpty()) plainText.append("\n");
                        plainText.append(t);
                    }
                }

                // Build html_text via projection
                String htmlText = htmlProjectionService.projectBlockList(windowBlocks);

                // Build hierarchy: walk ancestor chain collecting headings and sections
                ArrayNode hierarchy = objectMapper.createArrayNode();
                buildAncestorHierarchy(target, index, hierarchy);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("block_id", blockId);
                out.put("window_size", windowSize);
                out.put("plain_text", plainText.toString());
                out.put("html_text", htmlText);
                out.set("hierarchy", hierarchy);

                // Include summary of window blocks (block_id + type for each)
                ArrayNode windowMeta = out.putArray("window_blocks");
                for (DocumentComponent block : windowBlocks) {
                    ObjectNode meta = objectMapper.createObjectNode();
                    meta.put("block_id", block.getId());
                    meta.put("type", block.getType() != null ? block.getType().name() : "UNKNOWN");
                    meta.put("is_target", block.getId().equals(blockId));
                    String bt = DocumentTextSupport.extractText(block);
                    meta.put("text_preview", bt.length() > 120 ? bt.substring(0, 120) + "…" : bt);
                    windowMeta.add(meta);
                }
                return out;
            },
            "L3", true
        );
    }

    /** Collects the target block plus N preceding and N following siblings from the same parent. */
    private List<DocumentComponent> collectSiblingWindow(
        DocumentComponent target,
        Map<String, DocumentComponent> index,
        int windowSize
    ) {
        DocumentComponent parent = target.getParentId() != null ? index.get(target.getParentId()) : null;
        if (parent == null || parent.getChildren() == null) {
            return List.of(target);
        }
        List<DocumentComponent> siblings = parent.getChildren();
        int idx = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(target.getId())) { idx = i; break; }
        }
        if (idx < 0) return List.of(target);
        int from = Math.max(0, idx - windowSize);
        int to   = Math.min(siblings.size(), idx + windowSize + 1);
        return siblings.subList(from, to);
    }

    /**
     * Walks up the parent chain from {@code node} and pushes each HEADING or SECTION ancestor
     * (nearest-first, then reversed to root-first order) onto {@code out}.
     */
    private void buildAncestorHierarchy(
        DocumentComponent node,
        Map<String, DocumentComponent> index,
        ArrayNode out
    ) {
        List<ObjectNode> chain = new ArrayList<>();
        String parentId = node.getParentId();
        while (parentId != null) {
            DocumentComponent ancestor = index.get(parentId);
            if (ancestor == null) break;
            ComponentType type = ancestor.getType();
            if (type == ComponentType.HEADING || type == ComponentType.SECTION
                || type == ComponentType.DOCUMENT) {
                ObjectNode entry = objectMapper.createObjectNode();
                entry.put("block_id", ancestor.getId());
                entry.put("type", type.name());
                if (type == ComponentType.HEADING && ancestor.getLayoutProps() != null) {
                    entry.put("heading_level", ancestor.getLayoutProps().getHeadingLevel() != null
                        ? ancestor.getLayoutProps().getHeadingLevel() : 0);
                }
                entry.put("text", DocumentTextSupport.extractText(ancestor));
                if (ancestor.getAnchor() != null) {
                    entry.put("logical_path", ancestor.getAnchor().getLogicalPath());
                }
                chain.add(entry);
            }
            parentId = ancestor.getParentId();
        }
        // Reverse so root is first
        for (int i = chain.size() - 1; i >= 0; i--) {
            out.add(chain.get(i));
        }
    }

    // ─── get_document_outline ─────────────────────────────────────────────────

    private ToolDefinition getDocumentOutline() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("session_id").put("type", "string");
        schema.putArray("required").add("session_id");

        return new ToolDefinition(
            "get_document_outline",
            "Returns the full structural outline of the document as a JSON tree: sections, headings (with level and text), and leaf block IDs. Use this to understand the document structure before planning multi-block edits.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                ObjectNode out = objectMapper.createObjectNode();
                out.put("session_id", s.getSessionId());
                out.put("filename", s.getFilename());
                out.put("word_count", s.getWordCount());
                out.put("paragraph_count", s.getParagraphCount());
                out.set("outline", buildOutlineNode(s.getRoot()));
                return out;
            },
            "L3", true
        );
    }

    /**
     * Recursively builds the outline JSON tree for a component.
     * Headings and Sections are structural nodes; paragraphs/tables/lists are leaf nodes.
     */
    private ObjectNode buildOutlineNode(DocumentComponent c) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("block_id", c.getId());
        ComponentType type = c.getType();
        node.put("type", type != null ? type.name() : "UNKNOWN");

        if (type == ComponentType.HEADING) {
            int level = (c.getLayoutProps() != null && c.getLayoutProps().getHeadingLevel() != null)
                ? c.getLayoutProps().getHeadingLevel() : 1;
            node.put("heading_level", level);
            node.put("text", DocumentTextSupport.extractText(c));
            if (c.getStyleRef() != null) node.put("style_id", c.getStyleRef().getStyleId());
        } else if (type == ComponentType.SECTION || type == ComponentType.DOCUMENT) {
            String text = DocumentTextSupport.extractText(c);
            if (!text.isBlank()) node.put("text_preview", text.length() > 80 ? text.substring(0, 80) + "…" : text);
            if (c.getAnchor() != null) node.put("logical_path", c.getAnchor().getLogicalPath());
        } else {
            // Leaf-level block: paragraph, table, list item, image, etc.
            String text = DocumentTextSupport.extractText(c);
            if (!text.isBlank()) node.put("text_preview", text.length() > 120 ? text.substring(0, 120) + "…" : text);
            if (c.getStyleRef() != null) node.put("style_id", c.getStyleRef().getStyleId());
            if (c.getAnchor() != null) node.put("logical_path", c.getAnchor().getLogicalPath());
        }

        if (c.getChildren() != null && !c.getChildren().isEmpty()) {
            ArrayNode children = node.putArray("children");
            for (DocumentComponent child : c.getChildren()) {
                children.add(buildOutlineNode(child));
            }
        }
        return node;
    }

    // ─── get_style_context ────────────────────────────────────────────────────

    private ToolDefinition getStyleContext() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("block_id").put("type", "string")
            .put("description", "stableId of the block whose effective style to retrieve.");
        schema.putArray("required").add("session_id").add("block_id");

        return new ToolDefinition(
            "get_style_context",
            "Returns the effective style applied to a block: its OOXML style reference, resolved style definition (font, size, alignment, heading level, list type, etc.), and any inline layout overrides. Use this when the model needs to preserve or explicitly change formatting.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String blockId = params.path("block_id").asText();

                Map<String, DocumentComponent> index = anchorService.buildIndex(s.getRoot());
                DocumentComponent block = index.get(blockId);
                if (block == null) throw new IllegalArgumentException("Block not found: " + blockId);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("block_id", blockId);
                out.put("type", block.getType() != null ? block.getType().name() : "UNKNOWN");

                // Style reference
                StyleRef styleRef = block.getStyleRef();
                if (styleRef != null) {
                    out.put("style_id", styleRef.getStyleId());
                    out.put("style_name", styleRef.getStyleName());
                    out.put("has_inline_overrides", Boolean.TRUE.equals(styleRef.getHasInlineOverrides()));
                }

                // Resolve full style definition from registry
                ObjectNode resolvedStyle = out.putObject("resolved_style");
                if (styleRef != null && styleRef.getStyleId() != null) {
                    registryStore.findRegistry(s.getDocId()).ifPresent(reg -> {
                        StyleEntry entry = reg.getStyles() != null
                            ? reg.getStyles().get(styleRef.getStyleId()) : null;
                        if (entry != null) {
                            resolvedStyle.put("name", entry.getName());
                            resolvedStyle.put("type", entry.getType());
                            resolvedStyle.put("based_on", entry.getBasedOn());
                            if (entry.getFontAscii()  != null) resolvedStyle.put("font_ascii",   entry.getFontAscii());
                            if (entry.getFontEastAsia()!= null) resolvedStyle.put("font_east_asia", entry.getFontEastAsia());
                            if (entry.getSizePt()     != null) resolvedStyle.put("size_pt",      entry.getSizePt());
                            resolvedStyle.put("bold",        Boolean.TRUE.equals(entry.getBold()));
                            resolvedStyle.put("italic",      Boolean.TRUE.equals(entry.getItalic()));
                            resolvedStyle.put("underline",   Boolean.TRUE.equals(entry.getUnderline()));
                            resolvedStyle.put("strikethrough", Boolean.TRUE.equals(entry.getStrikethrough()));
                            if (entry.getColor()      != null) resolvedStyle.put("color",         entry.getColor());
                            if (entry.getAlignment()  != null) resolvedStyle.put("alignment",     entry.getAlignment());
                            if (entry.getSpacingBeforePt() != null) resolvedStyle.put("spacing_before_pt", entry.getSpacingBeforePt());
                            if (entry.getSpacingAfterPt()  != null) resolvedStyle.put("spacing_after_pt",  entry.getSpacingAfterPt());
                            if (entry.getLineSpacingPt()   != null) resolvedStyle.put("line_spacing_pt",   entry.getLineSpacingPt());
                            if (entry.getIndentLeftPt()    != null) resolvedStyle.put("indent_left_pt",    entry.getIndentLeftPt());
                        }
                    });
                }

                // Inline layout overrides from the block itself
                LayoutProps lp = block.getLayoutProps();
                ObjectNode inlineOverrides = out.putObject("inline_overrides");
                if (lp != null) {
                    if (lp.getHeadingLevel()  != null) inlineOverrides.put("heading_level",  lp.getHeadingLevel());
                    if (lp.getListType()      != null) inlineOverrides.put("list_type",       lp.getListType());
                    if (lp.getListLevel()     != null) inlineOverrides.put("list_level",      lp.getListLevel());
                    if (lp.getAlignment()     != null) inlineOverrides.put("alignment",       lp.getAlignment());
                    if (lp.getFontAscii()     != null) inlineOverrides.put("font_ascii",      lp.getFontAscii());
                    if (lp.getFontSizePt()    != null) inlineOverrides.put("font_size_pt",    lp.getFontSizePt());
                    if (lp.getBold()          != null) inlineOverrides.put("bold",            lp.getBold());
                    if (lp.getItalic()        != null) inlineOverrides.put("italic",          lp.getItalic());
                    if (lp.getUnderline()     != null) inlineOverrides.put("underline",       lp.getUnderline());
                    if (lp.getStrikethrough() != null) inlineOverrides.put("strikethrough",   lp.getStrikethrough());
                    if (lp.getColor()         != null) inlineOverrides.put("color",           lp.getColor());
                    if (lp.getIndentLeft()    != null) inlineOverrides.put("indent_left",     lp.getIndentLeft());
                    if (lp.getSpacingBefore() != null) inlineOverrides.put("spacing_before",  lp.getSpacingBefore());
                    if (lp.getSpacingAfter()  != null) inlineOverrides.put("spacing_after",   lp.getSpacingAfter());
                }

                return out;
            },
            "L3", true
        );
    }

    // ─── search_text ──────────────────────────────────────────────────────────

    private ToolDefinition searchText() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("query").put("type", "string")
            .put("description", "Text or phrase to search for (case-insensitive, semantic + keyword).");
        props.putObject("scope").put("type", "string")
            .put("description", "Optional block_id to limit search to a subtree (e.g. a specific section).");
        schema.putArray("required").add("session_id").add("query");

        return new ToolDefinition(
            "search_text",
            "Searches for text matching a query and returns matching blocks as plain text. Combines semantic and keyword search. Use when you need textual content without formatting markup.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String rawQuery = params.path("query").asText();
                String scopeId  = params.path("scope").asText(null);

                DocumentComponent searchRoot = resolveSearchRoot(s, scopeId);
                List<DocumentComponent> matches = collectTextMatches(s, searchRoot, rawQuery, 30);

                // Build plain_text: newline-separated text of all matched blocks
                StringBuilder plainText = new StringBuilder();
                ArrayNode matchNodes = objectMapper.createArrayNode();
                ArrayNode blockIds = objectMapper.createArrayNode();

                for (DocumentComponent match : matches) {
                    String text = DocumentTextSupport.extractText(match);
                    if (!plainText.isEmpty()) plainText.append("\n");
                    plainText.append(text);
                    blockIds.add(match.getId());

                    ObjectNode mn = objectMapper.createObjectNode();
                    mn.put("block_id", match.getId());
                    mn.put("type", match.getType() != null ? match.getType().name() : "UNKNOWN");
                    mn.put("text", text);
                    if (match.getAnchor() != null) mn.put("logical_path", match.getAnchor().getLogicalPath());
                    matchNodes.add(mn);
                }

                ObjectNode out = objectMapper.createObjectNode();
                out.put("query", rawQuery);
                out.put("match_count", matches.size());
                out.put("plain_text", plainText.toString());
                out.set("block_ids", blockIds);
                out.set("matches", matchNodes);
                return out;
            },
            "L3", true
        );
    }

    // ─── search_text_with_html ────────────────────────────────────────────────

    private ToolDefinition searchTextWithHtml() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        props.putObject("query").put("type", "string")
            .put("description", "Text or phrase to search for (case-insensitive, semantic + keyword).");
        props.putObject("scope").put("type", "string")
            .put("description", "Optional block_id to limit search to a subtree.");
        schema.putArray("required").add("session_id").add("query");

        return new ToolDefinition(
            "search_text_with_html",
            "Searches for text matching a query and returns matching blocks as both plain text and annotated HTML (preserving formatting: bold, italic, lists, headings, tables). Use when the model needs to understand or preserve the original formatting of matching content.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String rawQuery = params.path("query").asText();
                String scopeId  = params.path("scope").asText(null);

                DocumentComponent searchRoot = resolveSearchRoot(s, scopeId);
                List<DocumentComponent> matches = collectTextMatches(s, searchRoot, rawQuery, 30);

                StringBuilder plainText = new StringBuilder();
                ArrayNode matchNodes = objectMapper.createArrayNode();
                ArrayNode blockIds = objectMapper.createArrayNode();

                for (DocumentComponent match : matches) {
                    String text = DocumentTextSupport.extractText(match);
                    String html  = htmlProjectionService.projectBlock(match);
                    if (!plainText.isEmpty()) plainText.append("\n");
                    plainText.append(text);
                    blockIds.add(match.getId());

                    ObjectNode mn = objectMapper.createObjectNode();
                    mn.put("block_id", match.getId());
                    mn.put("type", match.getType() != null ? match.getType().name() : "UNKNOWN");
                    mn.put("text", text);
                    mn.put("html", html);
                    if (match.getAnchor() != null) mn.put("logical_path", match.getAnchor().getLogicalPath());
                    matchNodes.add(mn);
                }

                // Aggregate html_text for the whole result set
                String htmlText = htmlProjectionService.projectBlockList(matches);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("query", rawQuery);
                out.put("match_count", matches.size());
                out.put("plain_text", plainText.toString());
                out.put("html_text", htmlText);
                out.set("block_ids", blockIds);
                out.set("matches", matchNodes);
                return out;
            },
            "L3", true
        );
    }

    // ─── search helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the subtree root for a scoped search. If {@code scopeId} is null/blank,
     * returns the document root.
     */
    private DocumentComponent resolveSearchRoot(DocumentSession s, String scopeId) {
        if (scopeId == null || scopeId.isBlank()) return s.getRoot();
        Map<String, DocumentComponent> index = anchorService.buildIndex(s.getRoot());
        DocumentComponent scope = index.get(scopeId);
        if (scope == null) throw new IllegalArgumentException("Scope block not found: " + scopeId);
        return scope;
    }

    /**
     * Collects blocks matching {@code query} using semantic (if available) + keyword search,
     * capped at {@code limit} unique blocks, within the given {@code searchRoot} subtree.
     */
    private List<DocumentComponent> collectTextMatches(
        DocumentSession s,
        DocumentComponent searchRoot,
        String rawQuery,
        int limit
    ) {
        String lowerQuery = rawQuery.toLowerCase(Locale.ROOT);
        Set<String> seenIds = new LinkedHashSet<>();
        List<DocumentComponent> results = new ArrayList<>();

        // 1. Semantic search (returns blockIds from the full session, then filter by scope if needed)
        boolean scopedToRoot = searchRoot == s.getRoot();
        Map<String, DocumentComponent> index = scopedToRoot ? null : anchorService.buildIndex(s.getRoot());
        for (SemanticSearchMatch match : semanticSearchService.search(s, rawQuery, limit)) {
            if (seenIds.size() >= limit) break;
            // If scoped, verify the block lives within the scope subtree
            if (!scopedToRoot && index != null) {
                DocumentComponent block = index.get(match.blockId());
                if (block == null || !isDescendantOf(block, searchRoot.getId(), index)) continue;
            }
            if (seenIds.add(match.blockId())) {
                // Re-fetch from index to get the live DocumentComponent
                if (index == null) index = anchorService.buildIndex(s.getRoot());
                DocumentComponent block = index.get(match.blockId());
                if (block != null) results.add(block);
            }
        }

        // 2. Keyword search across the subtree
        collectKeywordMatches(searchRoot, lowerQuery, results, seenIds, limit);
        return results;
    }

    private void collectKeywordMatches(
        DocumentComponent node,
        String lowerQuery,
        List<DocumentComponent> results,
        Set<String> seenIds,
        int limit
    ) {
        if (results.size() >= limit || node == null) return;
        String text = DocumentTextSupport.extractText(node);
        if (!text.isBlank() && text.toLowerCase(Locale.ROOT).contains(lowerQuery)
            && seenIds.add(node.getId())) {
            results.add(node);
        }
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                collectKeywordMatches(child, lowerQuery, results, seenIds, limit);
            }
        }
    }

    /** Returns true if {@code node} is a descendant of the block with {@code ancestorId}. */
    private boolean isDescendantOf(
        DocumentComponent node,
        String ancestorId,
        Map<String, DocumentComponent> index
    ) {
        String parentId = node.getParentId();
        while (parentId != null) {
            if (parentId.equals(ancestorId)) return true;
            DocumentComponent parent = index.get(parentId);
            if (parent == null) break;
            parentId = parent.getParentId();
        }
        return false;
    }

    // ─── get_document_lines ───────────────────────────────────────────────────

    private ToolDefinition getDocumentLines() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("session_id").put("type", "string");
        schema.putArray("required").add("session_id");

        return new ToolDefinition(
            "get_document_lines",
            "Returns the document as a numbered list of lines, where each line is one block-level HTML element "
                + "(paragraph, heading, list item, table cell, etc.). Line numbers are 1-based and stable within "
                + "the current document state. Use the returned line numbers with REPLACE_TEXT_LINE or "
                + "REPLACE_BLOCK_LINE operations to target edits precisely without needing block IDs.",
            schema,
            params -> {
                DocumentSession s = requireSession(params);
                String sourceHtml = sessionStore
                    .findTextAsset(s.getSessionId(), FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME)
                    .orElseThrow(() -> new IllegalStateException(
                        "No source HTML found for session " + s.getSessionId()));

                org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(sourceHtml);
                List<DocumentLine> lines = lineExtractorService.extractLines(doc);

                ObjectNode out = objectMapper.createObjectNode();
                out.put("session_id", s.getSessionId());
                out.put("filename", s.getFilename());
                out.put("total_lines", lines.size());

                com.fasterxml.jackson.databind.node.ArrayNode linesArray = out.putArray("lines");
                for (DocumentLine line : lines) {
                    ObjectNode entry = objectMapper.createObjectNode();
                    entry.put("line_number", line.getLineNumber());
                    entry.put("text", line.getText());
                    entry.put("tag", line.getTag());
                    if (line.getBlockId() != null && !line.getBlockId().isBlank()) {
                        entry.put("block_id", line.getBlockId());
                    }
                    linesArray.add(entry);
                }
                return out;
            },
            "L3", true
        );
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
            "Returns full details of a revision: its patch operations, validation results, status, scope estimate, and structured diff metadata for the pending preview. Use before asking the user to confirm or reject a proposed edit.",
            schema,
            params -> {
                DocumentSession session = requireSession(params);
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
                    var affectedBlockIds = new java.util.LinkedHashSet<String>();
                    if (patch.getWorkingSet() != null) {
                        for (String blockId : patch.getWorkingSet()) {
                            if (blockId != null && !blockId.isBlank()) {
                                affectedBlockIds.add(blockId);
                            }
                        }
                    }

                    out.put("operation_count", patch.getOperations() != null ? patch.getOperations().size() : 0);
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
                    ArrayNode ops = out.putArray("operations");
                    if (patch.getOperations() != null) {
                        for (int opIdx = 0; opIdx < patch.getOperations().size(); opIdx++) {
                            PatchOperation op = patch.getOperations().get(opIdx);
                            ObjectNode opNode = objectMapper.createObjectNode();
                            opNode.put("op", op.getOp().name());
                            opNode.put("operation_index", opIdx);
                            opNode.put("description", op.getDescription() != null ? op.getDescription() : "");
                            if (op.getTarget() != null) {
                                ObjectNode targetNode = opNode.putObject("target");
                                if (op.getTarget().getBlockId() != null && !op.getTarget().getBlockId().isBlank()) {
                                    opNode.put("block_id", op.getTarget().getBlockId());
                                    targetNode.put("block_id", op.getTarget().getBlockId());
                                    affectedBlockIds.add(op.getTarget().getBlockId());
                                }
                                if (op.getTarget().getRunId() != null && !op.getTarget().getRunId().isBlank()) {
                                    targetNode.put("run_id", op.getTarget().getRunId());
                                }
                                if (op.getTarget().getStart() != null) {
                                    targetNode.put("start", op.getTarget().getStart());
                                }
                                if (op.getTarget().getEnd() != null) {
                                    targetNode.put("end", op.getTarget().getEnd());
                                }
                                if (op.getTarget().getTableId() != null && !op.getTarget().getTableId().isBlank()) {
                                    targetNode.put("table_id", op.getTarget().getTableId());
                                }
                                if (op.getTarget().getRowId() != null && !op.getTarget().getRowId().isBlank()) {
                                    targetNode.put("row_id", op.getTarget().getRowId());
                                }
                                if (op.getTarget().getCellId() != null && !op.getTarget().getCellId().isBlank()) {
                                    targetNode.put("cell_id", op.getTarget().getCellId());
                                }
                                if (op.getTarget().getCellLogicalAddress() != null && !op.getTarget().getCellLogicalAddress().isBlank()) {
                                    targetNode.put("cell_logical_address", op.getTarget().getCellLogicalAddress());
                                }
                            }
                            if (op.getValue() != null && !op.getValue().isNull()) {
                                opNode.set("value", op.getValue().deepCopy());
                            }
                            ops.add(opNode);
                        }
                    }

                    ArrayNode affected = out.putArray("affected_block_ids");
                    affectedBlockIds.forEach(affected::add);
                }

                if (revision.getStatus() == RevisionStatus.PENDING) {
                    out.set("preview", serializePendingRevisionPreview(revisionService.preview(revisionId, session), false));
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
                appendDiff(out, diff);
                return out;
            },
            "L3", true
        );
    }

    private ObjectNode serializePendingRevisionPreview(PendingRevisionPreview preview, boolean includeHtml) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("revision_id", preview.getRevisionId());
        out.put("session_id", preview.getSessionId());
        out.put("available", preview.isAvailable());
        if (preview.getBaseRevisionId() != null) {
            out.put("base_revision_id", preview.getBaseRevisionId());
        }
        if (preview.getCurrentRevisionId() != null) {
            out.put("current_revision_id", preview.getCurrentRevisionId());
        }
        if (preview.getMessage() != null && !preview.getMessage().isBlank()) {
            out.put("message", preview.getMessage());
        }
        if (includeHtml) {
            if (preview.getHtml() != null) {
                out.put("html", preview.getHtml());
            }
            if (preview.getSourceHtml() != null) {
                out.put("source_html", preview.getSourceHtml());
            }
        }
        if (preview.getValidation() != null) {
            out.set("validation", serializeValidation(preview.getValidation()));
        }
        if (preview.getDiff() != null) {
            ObjectNode diffNode = out.putObject("diff");
            appendDiff(diffNode, preview.getDiff());
        }
        return out;
    }

    private ObjectNode serializeValidation(PatchValidation validation) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("structure_ok", validation.isStructureOk());
        out.put("style_ok", validation.isStyleOk());
        out.put("scope", validation.getScope());
        ArrayNode errs = out.putArray("errors");
        if (validation.getErrors() != null) {
            validation.getErrors().forEach(errs::add);
        }
        ArrayNode warns = out.putArray("warnings");
        if (validation.getWarnings() != null) {
            validation.getWarnings().forEach(warns::add);
        }
        return out;
    }

    private void appendDiff(ObjectNode out, DocumentDiff diff) {
        out.put("base_revision_id", diff.getBaseRevisionId());
        out.put("target_revision_id", diff.getTargetRevisionId());
        out.put("text_edit_count", diff.getTextEditCount());
        out.put("style_edit_count", diff.getStyleEditCount());
        out.put("layout_edit_count", diff.getLayoutEditCount());
        out.put("has_conflicts", diff.isHasConflicts());

        ArrayNode textDiffs = out.putArray("text_diffs");
        if (diff.getTextDiffs() != null) {
            for (var td : diff.getTextDiffs()) {
                ObjectNode entry = objectMapper.createObjectNode();
                entry.put("block_id", td.getBlockId());
                entry.put("change_type", td.getChangeType());
                entry.put("old_text", td.getOldText());
                entry.put("new_text", td.getNewText());
                if (td.getOffset() != null) {
                    entry.put("offset", td.getOffset());
                }
                textDiffs.add(entry);
            }
        }

        ArrayNode styleDiffs = out.putArray("style_diffs");
        if (diff.getStyleDiffs() != null) {
            for (var sd : diff.getStyleDiffs()) {
                ObjectNode entry = objectMapper.createObjectNode();
                if (sd.getBlockId() != null) {
                    entry.put("block_id", sd.getBlockId());
                }
                if (sd.getRunId() != null) {
                    entry.put("run_id", sd.getRunId());
                }
                entry.put("property", sd.getProperty());
                entry.put("old_value", sd.getOldValue());
                entry.put("new_value", sd.getNewValue());
                styleDiffs.add(entry);
            }
        }

        ArrayNode layoutDiffs = out.putArray("layout_diffs");
        if (diff.getLayoutDiffs() != null) {
            for (var ld : diff.getLayoutDiffs()) {
                ObjectNode entry = objectMapper.createObjectNode();
                entry.put("block_id", ld.getBlockId());
                entry.put("change_type", ld.getChangeType());
                entry.put("old_value", ld.getOldValue());
                entry.put("new_value", ld.getNewValue());
                layoutDiffs.add(entry);
            }
        }
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
