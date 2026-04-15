package io.docpilot.mcp.personalization;

import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.session.DocumentSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DocumentSemanticChunkExtractor {

    private static final EnumSet<ComponentType> INDEXABLE_TYPES = EnumSet.of(
        ComponentType.HEADING,
        ComponentType.PARAGRAPH,
        ComponentType.LIST_ITEM,
        ComponentType.TABLE_CELL,
        ComponentType.HEADER,
        ComponentType.FOOTER,
        ComponentType.FOOTNOTE,
        ComponentType.HYPERLINK
    );

    private final AppProperties props;

    public List<DocumentSemanticChunk> extract(DocumentSession session) {
        List<DocumentSemanticChunk> chunks = new ArrayList<>();
        walk(session.getRoot(), session, List.of(), chunks);
        return chunks;
    }

    private void walk(
        DocumentComponent node,
        DocumentSession session,
        List<HeadingFrame> headings,
        List<DocumentSemanticChunk> chunks
    ) {
        if (node == null) {
            return;
        }

        List<HeadingFrame> activeHeadings = headings;
        if (node.getType() == ComponentType.HEADING) {
            String headingText = DocumentTextSupport.extractText(node);
            if (!headingText.isBlank()) {
                int headingLevel = node.getLayoutProps() != null && node.getLayoutProps().getHeadingLevel() != null
                    ? node.getLayoutProps().getHeadingLevel()
                    : 1;
                activeHeadings = updateHeadings(headings, headingLevel, headingText);
            }
        }

        if (INDEXABLE_TYPES.contains(node.getType())) {
            maybeAddChunk(node, session, activeHeadings, chunks);
        }

        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                walk(child, session, activeHeadings, chunks);
            }
        }
    }

    private void maybeAddChunk(
        DocumentComponent node,
        DocumentSession session,
        List<HeadingFrame> headings,
        List<DocumentSemanticChunk> chunks
    ) {
        String text = truncate(DocumentTextSupport.extractText(node));
        if (text.isBlank()) {
            return;
        }

        String headingPath = headingsToString(headings);
        String indexedText = headingPath.isBlank() ? text : truncate(headingPath + "\n" + text);

        chunks.add(new DocumentSemanticChunk(
            session.getSessionId() + "__" + node.getId(),
            session.getSessionId(),
            node.getId(),
            node.getType().name(),
            node.getAnchor() != null && node.getAnchor().getLogicalPath() != null
                ? node.getAnchor().getLogicalPath()
                : node.getId(),
            headingPath,
            text,
            indexedText
        ));
    }

    private List<HeadingFrame> updateHeadings(List<HeadingFrame> headings, int level, String text) {
        int normalisedLevel = Math.max(1, level);
        List<HeadingFrame> updated = new ArrayList<>();
        for (HeadingFrame heading : headings) {
            if (heading.level() < normalisedLevel) {
                updated.add(heading);
            }
        }
        updated.add(new HeadingFrame(normalisedLevel, text));
        return updated;
    }

    private String headingsToString(List<HeadingFrame> headings) {
        List<String> parts = new ArrayList<>();
        for (HeadingFrame heading : headings) {
            if (!heading.text().isBlank()) {
                parts.add(heading.text());
            }
        }
        return String.join(" > ", parts);
    }

    private String truncate(String value) {
        String normalised = DocumentTextSupport.normaliseWhitespace(value);
        int maxIndexedChars = props.personalization().maxIndexedChars();
        return normalised.length() <= maxIndexedChars ? normalised : normalised.substring(0, maxIndexedChars);
    }

    private record HeadingFrame(int level, String text) {
    }
}