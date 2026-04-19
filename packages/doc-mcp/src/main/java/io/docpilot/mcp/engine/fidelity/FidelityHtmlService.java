package io.docpilot.mcp.engine.fidelity;

import com.fasterxml.jackson.databind.JsonNode;
import io.docpilot.mcp.engine.line.LineExtractorService;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.patch.OperationType;
import io.docpilot.mcp.model.patch.Patch;
import io.docpilot.mcp.model.patch.PatchOperation;
import io.docpilot.mcp.model.patch.PatchTarget;
import io.docpilot.mcp.model.session.DocumentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class FidelityHtmlService {

    private final LineExtractorService lineExtractorService;

    public static final String CURRENT_SOURCE_ASSET_NAME = "source.html";
    public static final String ORIGINAL_SOURCE_ASSET_NAME = "source.original.html";
    public static final String CURRENT_ANALYSIS_ASSET_NAME = "analysis.html";

    private static final int CANDIDATE_WINDOW = 48;
    private static final EnumSet<ComponentType> RENDERABLE_TYPES = EnumSet.of(
        ComponentType.HEADING,
        ComponentType.PARAGRAPH,
        ComponentType.LIST_ITEM,
        ComponentType.TABLE,
        ComponentType.TABLE_ROW,
        ComponentType.TABLE_CELL,
        ComponentType.IMAGE,
        ComponentType.HYPERLINK
    );

    public static String revisionSourceSnapshotAssetName(String revisionId) {
        return "source.snapshot." + revisionId + ".html";
    }

    public String annotateForSession(String html, DocumentSession session) {
        Document document = parseDocument(html);
        Element body = document.body();
        if (body == null || session == null || session.getRoot() == null) {
            return document.outerHtml();
        }

        clearExistingDocpilotAttrs(body);

        List<DocumentComponent> components = new ArrayList<>();
        collectRenderableComponents(session.getRoot(), components);
        List<Element> candidates = collectCandidates(body);
        Set<Integer> usedCandidateIndexes = new HashSet<>();

        int cursor = 0;
        for (DocumentComponent component : components) {
            int candidateIndex = findBestCandidate(component, candidates, usedCandidateIndexes, cursor);
            if (candidateIndex < 0) {
                continue;
            }

            Element candidate = candidates.get(candidateIndex);
            applyDocpilotAttrs(candidate, component);
            usedCandidateIndexes.add(candidateIndex);
            cursor = candidateIndex + 1;
        }

        return document.outerHtml();
    }

    public String applyPatch(String html, Patch patch, DocumentSession session) {
        Document document = parseDocument(html);

        if (patch != null && patch.getOperations() != null) {
            for (PatchOperation operation : patch.getOperations()) {
                applyOperation(document, operation);
            }
        }

        return annotateForSession(document.outerHtml(), session);
    }

    private void applyOperation(Document document, PatchOperation operation) {
        if (operation == null || operation.getTarget() == null || operation.getOp() == null) {
            return;
        }

        switch (operation.getOp()) {
            case REPLACE_TEXT_RANGE, UPDATE_CELL_CONTENT -> replaceTextRange(document, operation);
            case INSERT_TEXT_AT -> insertText(document, operation);
            case DELETE_TEXT_RANGE -> deleteTextRange(document, operation);
            case DELETE_BLOCK -> deleteBlock(document, operation.getTarget());
            case MOVE_BLOCK -> moveBlock(document, operation);
            case CLONE_BLOCK -> cloneBlock(document, operation);
            case CREATE_BLOCK -> createBlock(document, operation);
            case INSERT_ROW -> insertRow(document, operation.getTarget());
            case DELETE_ROW -> deleteRow(document, operation.getTarget());
            case REPLACE_TEXT_LINE -> applyReplaceTextLine(document, operation);
            case REPLACE_BLOCK_LINE -> applyReplaceBlockLine(document, operation);
            default -> {
                // For style-only operations we keep the existing HTML untouched to preserve source fidelity.
            }
        }
    }

    private void replaceTextRange(Document document, PatchOperation operation) {
        Element target = resolveTextTarget(document, operation.getTarget());
        if (target == null) {
            return;
        }
        mutateTextRange(
            target,
            operation.getTarget().getStart(),
            operation.getTarget().getEnd(),
            extractTextValue(operation)
        );
    }

    private void insertText(Document document, PatchOperation operation) {
        Element target = resolveTextTarget(document, operation.getTarget());
        if (target == null) {
            return;
        }
        Integer start = operation.getTarget().getStart();
        mutateTextRange(target, start, start, extractTextValue(operation));
    }

    private void deleteTextRange(Document document, PatchOperation operation) {
        Element target = resolveTextTarget(document, operation.getTarget());
        if (target == null) {
            return;
        }
        mutateTextRange(target, operation.getTarget().getStart(), operation.getTarget().getEnd(), "");
    }

    private void deleteBlock(Document document, PatchTarget target) {
        Element element = resolveElement(document, target);
        if (element != null) {
            element.remove();
        }
    }

    private void moveBlock(Document document, PatchOperation operation) {
        Element target = resolveElement(document, operation.getTarget());
        if (target == null || operation.getValue() == null) {
            return;
        }

        String targetHtml = target.outerHtml();
        String afterId = stringField(operation, "afterBlockId", "after_block_id");
        String beforeId = stringField(operation, "beforeBlockId", "before_block_id");
        String referenceId = afterId != null ? afterId : beforeId;
        if (referenceId == null) {
            return;
        }

        Element reference = findByDocpilotId(document, referenceId);
        if (reference == null) {
            return;
        }

        target.remove();
        if (afterId != null) {
            reference.after(targetHtml);
        } else {
            reference.before(targetHtml);
        }
    }

    private void cloneBlock(Document document, PatchOperation operation) {
        Element target = resolveElement(document, operation.getTarget());
        if (target == null) {
            return;
        }

        String cloneHtml = target.outerHtml();
        String afterId = stringField(operation, "afterBlockId", "after_block_id");
        String beforeId = stringField(operation, "beforeBlockId", "before_block_id");

        if (afterId != null || beforeId != null) {
            Element reference = findByDocpilotId(document, afterId != null ? afterId : beforeId);
            if (reference == null) {
                return;
            }
            if (afterId != null) {
                reference.after(cloneHtml);
            } else {
                reference.before(cloneHtml);
            }
            return;
        }

        target.after(cloneHtml);
    }

    private void createBlock(Document document, PatchOperation operation) {
        Element target = resolveElement(document, operation.getTarget());
        if (target == null) {
            return;
        }

        String html = stringField(operation, "html");
        if (html != null && !html.isBlank()) {
            target.after(html);
            return;
        }

        String created = buildCreatedElementHtml(target, operation);
        if (!created.isBlank()) {
            target.after(created);
        }
    }

    private void insertRow(Document document, PatchTarget target) {
        Element row = resolveRowElement(document, target);
        Element table = row != null ? row.parent() : resolveElement(document, target);

        if (row == null && table != null && !"table".equals(table.normalName())) {
            table = table.closest("table");
        }
        if (row == null && table != null) {
            row = table.selectFirst("tr");
        }
        if (row == null) {
            return;
        }

        Element clone = row.clone();
        clearTextNodes(clone);
        row.after(clone.outerHtml());
    }

    private void deleteRow(Document document, PatchTarget target) {
        Element row = resolveRowElement(document, target);
        if (row != null) {
            row.remove();
        }
    }

    // ── Line-based operations ─────────────────────────────────────────────────

    /**
     * Replaces the text content of the block element at the given line number,
     * preserving the outer HTML wrapper (tag, class, inline style, data attributes).
     * value: {old_text, new_text}
     */
    private void applyReplaceTextLine(Document document, PatchOperation operation) {
        Integer lineNumber = operation.getTarget() != null ? operation.getTarget().getLineNumber() : null;
        JsonNode value = operation.getValue();
        String newText = value != null ? value.path("new_text").asText(null) : null;
        if (lineNumber == null || lineNumber < 1 || newText == null) {
            log.warn("REPLACE_TEXT_LINE: missing or invalid target.line_number/value.new_text");
            return;
        }

        List<Element> blocks = lineExtractorService.getBlockElements(document);
        if (lineNumber > blocks.size()) {
            log.warn("REPLACE_TEXT_LINE: line {} out of range (document has {} lines)", lineNumber, blocks.size());
            return;
        }

        Element el = blocks.get(lineNumber - 1);
        el.text(newText);  // Replaces all inner content; keeps tag, class, style, data-* attrs.
    }

    /**
     * Replaces the entire block element at the given line number with AI-provided HTML.
     * The data-doc-node-id attribute from the old element is preserved if present.
     * value: {html}
     */
    private void applyReplaceBlockLine(Document document, PatchOperation operation) {
        Integer lineNumber = operation.getTarget() != null ? operation.getTarget().getLineNumber() : null;
        JsonNode value = operation.getValue();
        String newHtml = value != null ? value.path("html").asText(null) : null;
        if (lineNumber == null || lineNumber < 1 || newHtml == null || newHtml.isBlank()) {
            log.warn("REPLACE_BLOCK_LINE: missing or invalid target.line_number/value.html");
            return;
        }

        List<Element> blocks = lineExtractorService.getBlockElements(document);
        if (lineNumber > blocks.size()) {
            log.warn("REPLACE_BLOCK_LINE: line {} out of range (document has {} lines)", lineNumber, blocks.size());
            return;
        }

        Element el = blocks.get(lineNumber - 1);
        String blockId = el.attr("data-doc-node-id");

        Element newEl = Jsoup.parseBodyFragment(newHtml).body().children().first();
        if (newEl == null) {
            log.warn("REPLACE_BLOCK_LINE: provided html produced no block element");
            return;
        }
        // Preserve the docpilot annotation so the component tree reference stays valid.
        if (!blockId.isBlank()) {
            newEl.attr("data-doc-node-id", blockId);
        }
        el.replaceWith(newEl);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Element resolveTextTarget(Document document, PatchTarget target) {
        Element targetElement = resolveElement(document, target);
        if (targetElement == null) {
            return null;
        }

        if ("td".equals(targetElement.normalName()) || "th".equals(targetElement.normalName())) {
            Element nested = targetElement.selectFirst("p,li,h1,h2,h3,h4,h5,h6,div");
            if (nested != null) {
                return nested;
            }
        }

        return targetElement;
    }

    private Element resolveElement(Document document, PatchTarget target) {
        if (target == null) {
            return null;
        }

        if (target.getRunId() != null) {
            Element byRun = findByDocpilotId(document, target.getRunId());
            if (byRun != null) {
                return byRun;
            }
        }
        if (target.getBlockId() != null) {
            Element byBlock = findByDocpilotId(document, target.getBlockId());
            if (byBlock != null) {
                return byBlock;
            }
        }
        if (target.getCellId() != null) {
            Element byCell = findByDocpilotId(document, target.getCellId());
            if (byCell != null) {
                return byCell;
            }
        }
        if (target.getRowId() != null) {
            Element byRow = findByDocpilotId(document, target.getRowId());
            if (byRow != null) {
                return byRow;
            }
        }
        if (target.getTableId() != null) {
            return findByDocpilotId(document, target.getTableId());
        }
        return null;
    }

    private Element resolveRowElement(Document document, PatchTarget target) {
        if (target == null) {
            return null;
        }
        if (target.getRowId() != null) {
            Element byRow = findByDocpilotId(document, target.getRowId());
            if (byRow != null) {
                return byRow;
            }
        }

        Element resolved = resolveElement(document, target);
        if (resolved == null) {
            return null;
        }
        if ("tr".equals(resolved.normalName())) {
            return resolved;
        }
        return resolved.closest("tr");
    }

    private Element findByDocpilotId(Document document, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return document.selectFirst("[data-doc-node-id=\"" + cssEscape(id) + "\"], [data-anchor=\"" + cssEscape(id) + "\"]");
    }

    private void mutateTextRange(Element target, Integer startValue, Integer endValue, String replacement) {
        List<TextSegment> segments = new ArrayList<>();
        collectTextSegments(target, segments, 0);
        int totalLength = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).end();
        int start = clamp(startValue != null ? startValue : 0, 0, totalLength);
        int end = clamp(endValue != null ? endValue : totalLength, start, totalLength);

        if (start == end) {
            insertAtOffset(target, segments, start, replacement);
            pruneEmptyTextNodes(target);
            return;
        }

        if (segments.isEmpty()) {
            target.text(replacement);
            return;
        }

        boolean replacementInserted = replacement == null || replacement.isEmpty();
        for (TextSegment segment : segments) {
            if (segment.end() <= start || segment.start() >= end) {
                continue;
            }

            String text = segment.node().getWholeText();
            int localStart = Math.max(0, start - segment.start());
            int localEnd = Math.min(text.length(), end - segment.start());
            String prefix = text.substring(0, localStart);
            String suffix = text.substring(Math.max(localEnd, localStart));

            if (!replacementInserted) {
                segment.node().text(prefix + replacement + suffix);
                replacementInserted = true;
            } else {
                segment.node().text(prefix + suffix);
            }
        }

        if (!replacementInserted && replacement != null && !replacement.isEmpty()) {
            insertAtOffset(target, segments, end, replacement);
        }
        pruneEmptyTextNodes(target);
    }

    private void insertAtOffset(Element target, List<TextSegment> segments, int offset, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        if (segments.isEmpty()) {
            target.appendText(text);
            return;
        }

        int totalLength = segments.get(segments.size() - 1).end();
        if (offset <= 0) {
            segments.get(0).node().before(text);
            return;
        }
        if (offset >= totalLength) {
            segments.get(segments.size() - 1).node().after(text);
            return;
        }

        for (TextSegment segment : segments) {
            if (offset < segment.start() || offset > segment.end()) {
                continue;
            }

            String current = segment.node().getWholeText();
            int local = Math.max(0, Math.min(current.length(), offset - segment.start()));
            segment.node().text(current.substring(0, local) + text + current.substring(local));
            return;
        }

        segments.get(segments.size() - 1).node().after(text);
    }

    private void collectTextSegments(Node node, List<TextSegment> segments, int offset) {
        int cursor = offset;
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode textNode) {
                String text = textNode.getWholeText();
                if (shouldTrackTextNode(textNode, text)) {
                    segments.add(new TextSegment(textNode, cursor, cursor + text.length()));
                    cursor += text.length();
                }
                continue;
            }

            if (child instanceof Element element) {
                int nestedStart = cursor;
                collectTextSegments(element, segments, nestedStart);
                cursor = segments.isEmpty() ? nestedStart : segments.get(segments.size() - 1).end();
            }
        }
    }

    private boolean shouldTrackTextNode(TextNode textNode, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (!text.trim().isEmpty()) {
            return true;
        }
        Node parentNode = textNode.parent();
        if (!(parentNode instanceof Element parent)) {
            return false;
        }
        return switch (parent.normalName()) {
            case "p", "li", "span", "a", "strong", "em", "u", "s", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6" -> true;
            default -> false;
        };
    }

    private void pruneEmptyTextNodes(Node node) {
        List<Node> children = new ArrayList<>(node.childNodes());
        for (Node child : children) {
            if (child instanceof TextNode textNode) {
                if (textNode.getWholeText().isEmpty()) {
                    textNode.remove();
                }
                continue;
            }
            pruneEmptyTextNodes(child);
        }
    }

    private void clearTextNodes(Node node) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode textNode) {
                textNode.text("");
            } else {
                clearTextNodes(child);
            }
        }
    }

    private String buildCreatedElementHtml(Element template, PatchOperation operation) {
        ComponentType requestedType = componentType(stringField(operation, "type"));
        String text = extractTextValue(operation);

        Element created;
        if (requestedType == null || compatibleTypeForTag(requestedType, template)) {
            created = template.clone();
            clearTextNodes(created);
        } else {
            created = new Element(tagFor(requestedType));
        }

        if (text != null && !text.isBlank()) {
            mutateTextRange(created, 0, null, text);
        }
        return created.outerHtml();
    }

    private ComponentType componentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ComponentType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String tagFor(ComponentType type) {
        return switch (type) {
            case HEADING -> "h2";
            case LIST_ITEM -> "li";
            case TABLE -> "table";
            case TABLE_ROW -> "tr";
            case TABLE_CELL -> "td";
            case IMAGE -> "img";
            case HYPERLINK -> "a";
            default -> "p";
        };
    }

    private boolean compatibleTypeForTag(ComponentType type, Element element) {
        return isCompatible(type, element, true);
    }

    private int findBestCandidate(
        DocumentComponent component,
        List<Element> candidates,
        Set<Integer> usedCandidateIndexes,
        int cursor
    ) {
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        int endExclusive = Math.min(candidates.size(), cursor + CANDIDATE_WINDOW);

        for (int index = cursor; index < endExclusive; index++) {
            if (usedCandidateIndexes.contains(index)) {
                continue;
            }
            Element candidate = candidates.get(index);
            int score = scoreCandidate(component, candidate, index - cursor);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }

        if (bestIndex >= 0 && bestScore > Integer.MIN_VALUE) {
            return bestIndex;
        }

        for (int index = 0; index < candidates.size(); index++) {
            if (usedCandidateIndexes.contains(index)) {
                continue;
            }
            Element candidate = candidates.get(index);
            int score = scoreCandidate(component, candidate, Math.abs(index - cursor));
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestScore > Integer.MIN_VALUE ? bestIndex : -1;
    }

    private int scoreCandidate(DocumentComponent component, Element candidate, int distance) {
        if (component.getType() == null || !isCompatible(component.getType(), candidate, false)) {
            return Integer.MIN_VALUE;
        }

        int score = 100 - Math.min(distance, 90);
        if (isPreferredTag(component.getType(), candidate)) {
            score += 25;
        }

        String componentText = normalizeText(componentText(component));
        String candidateText = normalizeText(candidate.text());
        if (!componentText.isEmpty()) {
            if (componentText.equals(candidateText)) {
                score += 80;
            } else if (candidateText.contains(componentText) || componentText.contains(candidateText)) {
                score += 35;
            }
        } else if (candidateText.isEmpty()) {
            score += 10;
        }

        if (component.getType() == ComponentType.HEADING && candidate.normalName().matches("h[1-6]")) {
            score += 10;
        }
        return score;
    }

    private boolean isCompatible(ComponentType type, Element candidate, boolean relaxed) {
        String tag = candidate.normalName();
        return switch (type) {
            case HEADING -> tag.matches("h[1-6]") || (relaxed && ("p".equals(tag) || isParagraphLikeDiv(candidate)));
            case PARAGRAPH -> "p".equals(tag) || (relaxed && isParagraphLikeDiv(candidate));
            case LIST_ITEM -> "li".equals(tag);
            case TABLE -> "table".equals(tag);
            case TABLE_ROW -> "tr".equals(tag);
            case TABLE_CELL -> "td".equals(tag) || "th".equals(tag);
            case IMAGE -> "img".equals(tag);
            case HYPERLINK -> "a".equals(tag);
            default -> false;
        };
    }

    private boolean isPreferredTag(ComponentType type, Element candidate) {
        return switch (type) {
            case HEADING -> candidate.normalName().matches("h[1-6]");
            case PARAGRAPH -> "p".equals(candidate.normalName());
            case LIST_ITEM -> "li".equals(candidate.normalName());
            case TABLE -> "table".equals(candidate.normalName());
            case TABLE_ROW -> "tr".equals(candidate.normalName());
            case TABLE_CELL -> "td".equals(candidate.normalName()) || "th".equals(candidate.normalName());
            case IMAGE -> "img".equals(candidate.normalName());
            case HYPERLINK -> "a".equals(candidate.normalName());
            default -> false;
        };
    }

    private boolean isParagraphLikeDiv(Element candidate) {
        if (!"div".equals(candidate.normalName())) {
            return false;
        }
        return candidate.selectFirst("p,li,table,tr,td,th,h1,h2,h3,h4,h5,h6") == null;
    }

    private String componentText(DocumentComponent component) {
        if (component.getContentProps() != null && component.getContentProps().getText() != null) {
            return component.getContentProps().getText();
        }
        if (component.getChildren() == null || component.getChildren().isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (DocumentComponent child : component.getChildren()) {
            text.append(componentText(child));
        }
        return text.toString();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private List<Element> collectCandidates(Element body) {
        List<Element> candidates = new ArrayList<>();
        for (Element element : body.getAllElements()) {
            if (element == body) {
                continue;
            }
            String tag = element.normalName();
            if (tag.matches("h[1-6]")
                || "p".equals(tag)
                || "li".equals(tag)
                || "table".equals(tag)
                || "tr".equals(tag)
                || "td".equals(tag)
                || "th".equals(tag)
                || "img".equals(tag)
                || "a".equals(tag)
                || isParagraphLikeDiv(element)) {
                candidates.add(element);
            }
        }
        return candidates;
    }

    private void collectRenderableComponents(DocumentComponent component, List<DocumentComponent> collected) {
        if (component == null) {
            return;
        }
        if (component.getType() != null && RENDERABLE_TYPES.contains(component.getType())) {
            collected.add(component);
        }
        if (component.getChildren() == null) {
            return;
        }
        for (DocumentComponent child : component.getChildren()) {
            collectRenderableComponents(child, collected);
        }
    }

    private void clearExistingDocpilotAttrs(Element body) {
        for (Element element : body.select("[data-doc-node-id],[data-anchor],[data-doc-node-type],[data-style-ref],[data-logical-path]")) {
            element.removeAttr("data-doc-node-id");
            element.removeAttr("data-anchor");
            element.removeAttr("data-doc-node-type");
            element.removeAttr("data-style-ref");
            element.removeAttr("data-logical-path");
        }
    }

    private void applyDocpilotAttrs(Element element, DocumentComponent component) {
        element.attr("data-doc-node-id", component.getId() != null ? component.getId() : "");
        element.attr("data-anchor", component.getId() != null ? component.getId() : "");
        if (component.getType() != null) {
            element.attr("data-doc-node-type", component.getType().name().toLowerCase(Locale.ROOT));
        }
        if (component.getStyleRef() != null && component.getStyleRef().getStyleId() != null) {
            element.attr("data-style-ref", component.getStyleRef().getStyleId());
        }
        if (component.getAnchor() != null && component.getAnchor().getLogicalPath() != null) {
            element.attr("data-logical-path", component.getAnchor().getLogicalPath());
        }
    }

    private Document parseDocument(String html) {
        String source = html == null ? "" : html;
        Document document = source.contains("<html") || source.contains("<!DOCTYPE")
            ? Jsoup.parse(source)
            : Jsoup.parse("<!DOCTYPE html><html><head></head><body>" + source + "</body></html>");
        document.outputSettings().prettyPrint(false);
        return document;
    }

    private String extractTextValue(PatchOperation operation) {
        if (operation == null || operation.getValue() == null || operation.getValue().isNull()) {
            return "";
        }
        if (operation.getValue().isTextual()) {
            return operation.getValue().asText();
        }
        return stringField(operation, "text", "newText", "new_text") != null
            ? stringField(operation, "text", "newText", "new_text")
            : "";
    }

    private String stringField(PatchOperation operation, String... fieldNames) {
        if (operation == null || operation.getValue() == null || operation.getValue().isNull()) {
            return null;
        }
        if (operation.getValue().isTextual() && fieldNames.length == 0) {
            return operation.getValue().asText();
        }
        for (String fieldName : fieldNames) {
            if (operation.getValue().hasNonNull(fieldName)) {
                return operation.getValue().get(fieldName).asText();
            }
        }
        return null;
    }

    private String cssEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record TextSegment(TextNode node, int start, int end) {}
}