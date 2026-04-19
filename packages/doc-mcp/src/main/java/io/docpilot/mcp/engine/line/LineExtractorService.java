package io.docpilot.mcp.engine.line;

import io.docpilot.mcp.model.document.DocumentLine;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Extracts "lines" from fidelity HTML, where each line corresponds to one
 * block-level HTML element traversed in document order.
 *
 * <p>Block-level elements (p, h1-h6, li, td, th, caption, blockquote, pre,
 * dt, dd) are treated as atomic lines. Traversal stops at the first block
 * element encountered in any subtree — nested block elements within a
 * recognised block tag are not recursed into.
 *
 * <p>Line numbers are 1-based and stable within a single document state.
 */
@Service
public class LineExtractorService {

    /**
     * HTML tag names that are treated as "line" boundaries.
     * Each occurrence of these tags (in document order) produces one line.
     */
    public static final Set<String> BLOCK_TAGS = Set.of(
        "p", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "td", "th", "caption", "blockquote", "pre", "dt", "dd"
    );

    // -----------------------------------------------------------------------
    //  Line extraction
    // -----------------------------------------------------------------------

    /**
     * Extracts an ordered list of document lines from a parsed Jsoup {@link Document}.
     * Prefer this overload when you already have a parsed document to avoid re-parsing.
     */
    public List<DocumentLine> extractLines(Document doc) {
        List<DocumentLine> lines = new ArrayList<>();
        int[] counter = {0};
        collectLinesRecurse(doc.body(), lines, counter);
        return lines;
    }

    /**
     * Returns the raw Jsoup {@link Element} objects (in document order) for all
     * block-level elements found in the given document.
     *
     * <p>These are live references into the document — mutating them mutates the
     * document in place.
     */
    public List<Element> getBlockElements(Document doc) {
        List<Element> blocks = new ArrayList<>();
        collectBlockElementsRecurse(doc.body(), blocks);
        return blocks;
    }

    /**
     * Returns the total number of lines in the given document.
     */
    public int countLines(Document doc) {
        return getBlockElements(doc).size();
    }

    // -----------------------------------------------------------------------
    //  Private traversal helpers
    // -----------------------------------------------------------------------

    private void collectLinesRecurse(Element el, List<DocumentLine> lines, int[] counter) {
        if (BLOCK_TAGS.contains(el.normalName())) {
            counter[0]++;
            lines.add(DocumentLine.builder()
                .lineNumber(counter[0])
                .text(el.text())
                .outerHtml(el.outerHtml())
                .tag(el.normalName())
                .blockId(el.attr("data-doc-node-id"))
                .build());
            // Do not recurse into block elements — each is its own line.
            return;
        }
        for (Element child : el.children()) {
            collectLinesRecurse(child, lines, counter);
        }
    }

    private void collectBlockElementsRecurse(Element el, List<Element> result) {
        if (BLOCK_TAGS.contains(el.normalName())) {
            result.add(el);
            return;
        }
        for (Element child : el.children()) {
            collectBlockElementsRecurse(child, result);
        }
    }
}
