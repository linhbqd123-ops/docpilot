package io.docpilot.mcp.engine.diff;

import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.model.diff.*;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.document.LayoutProps;
import io.docpilot.mcp.model.document.StyleRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes a three-layer {@link DocumentDiff} between two component trees
 * (representing the state before and after a patch/revision).
 *
 * <h3>Diff layers</h3>
 * <ol>
 *   <li><b>Text diff</b> — character-level changes inside text runs / paragraphs.</li>
 *   <li><b>Style diff</b> — changes to style IDs or inline formatting properties.</li>
 *   <li><b>Layout diff</b> — structural changes: heading levels, list levels, table rows, breaks.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiffService {

    private final AnchorService anchorService;

    /**
     * Computes the diff between {@code before} and {@code after}.
     * Both trees must represent the same session; nodes are matched by {@code stableId}.
     */
    public DocumentDiff compute(DocumentComponent before,
                                 DocumentComponent after,
                                 String baseRevisionId,
                                 String targetRevisionId,
                                 String sessionId) {
        Map<String, DocumentComponent> beforeIndex = anchorService.buildIndex(before);
        Map<String, DocumentComponent> afterIndex  = anchorService.buildIndex(after);

        List<TextDiffEntry>   textDiffs   = new ArrayList<>();
        List<StyleDiffEntry>  styleDiffs  = new ArrayList<>();
        List<LayoutDiffEntry> layoutDiffs = new ArrayList<>();

        // Walk all nodes in the "after" tree
        for (Map.Entry<String, DocumentComponent> entry : afterIndex.entrySet()) {
            String id = entry.getKey();
            DocumentComponent afterNode  = entry.getValue();
            DocumentComponent beforeNode = beforeIndex.get(id);

            if (beforeNode == null) {
                // New block added
                layoutDiffs.add(LayoutDiffEntry.builder()
                    .blockId(id)
                    .changeType("BLOCK_ADDED")
                    .newValue(afterNode.getType() != null ? afterNode.getType().name() : "?")
                    .build());
                continue;
            }

            diffText(beforeNode, afterNode, textDiffs);
            diffStyle(beforeNode, afterNode, styleDiffs);
            diffLayout(beforeNode, afterNode, layoutDiffs);
        }

        // Detect deleted blocks
        for (String id : beforeIndex.keySet()) {
            if (!afterIndex.containsKey(id)) {
                DocumentComponent deletedNode = beforeIndex.get(id);
                layoutDiffs.add(LayoutDiffEntry.builder()
                    .blockId(id)
                    .changeType("BLOCK_DELETED")
                    .oldValue(deletedNode.getType() != null ? deletedNode.getType().name() : "?")
                    .build());
            }
        }

        return DocumentDiff.builder()
            .baseRevisionId(baseRevisionId)
            .targetRevisionId(targetRevisionId)
            .sessionId(sessionId)
            .textDiffs(textDiffs)
            .styleDiffs(styleDiffs)
            .layoutDiffs(layoutDiffs)
            .textEditCount(textDiffs.size())
            .styleEditCount(styleDiffs.size())
            .layoutEditCount(layoutDiffs.size())
            .hasConflicts(false)
            .build();
    }

    // -----------------------------------------------------------------------
    //  Type-specific diff helpers
    // -----------------------------------------------------------------------

    private void diffText(DocumentComponent before, DocumentComponent after, List<TextDiffEntry> out) {
        String oldText = textOf(before);
        String newText = textOf(after);
        if (oldText == null && newText == null) return;
        if (Objects.equals(oldText, newText)) return;

        if (oldText == null) {
            out.add(TextDiffEntry.builder().blockId(after.getId()).changeType("ADD").newText(newText).offset(0).build());
        } else if (newText == null) {
            out.add(TextDiffEntry.builder().blockId(before.getId()).changeType("DELETE").oldText(oldText).offset(0).build());
        } else {
            // Simple full-block diff (word-level LCS is deferred to a future iteration)
            out.add(TextDiffEntry.builder().blockId(after.getId()).changeType("REPLACE")
                .oldText(oldText).newText(newText).offset(0).build());
        }
    }

    private void diffStyle(DocumentComponent before, DocumentComponent after, List<StyleDiffEntry> out) {
        String oldStyle = styleId(before);
        String newStyle = styleId(after);
        if (oldStyle != null || newStyle != null) {
            if (!Objects.equals(oldStyle, newStyle)) {
                out.add(StyleDiffEntry.builder()
                    .blockId(after.getId())
                    .property("styleId")
                    .oldValue(oldStyle)
                    .newValue(newStyle)
                    .build());
            }
        }

        // Inline format properties
        LayoutProps bl = before.getLayoutProps();
        LayoutProps al = after.getLayoutProps();
        if (bl == null && al == null) return;

        compareStyleProp(after.getId(), "bold",          str(bl, "bold"),          str(al, "bold"),          out);
        compareStyleProp(after.getId(), "italic",        str(bl, "italic"),        str(al, "italic"),        out);
        compareStyleProp(after.getId(), "fontAscii",     str(bl, "fontAscii"),     str(al, "fontAscii"),     out);
        compareStyleProp(after.getId(), "fontSizePt",    str(bl, "fontSizePt"),    str(al, "fontSizePt"),    out);
        compareStyleProp(after.getId(), "color",         str(bl, "color"),         str(al, "color"),         out);
        compareStyleProp(after.getId(), "alignment",     str(bl, "alignment"),     str(al, "alignment"),     out);
        compareStyleProp(after.getId(), "spacingBefore", str(bl, "spacingBefore"), str(al, "spacingBefore"), out);
        compareStyleProp(after.getId(), "spacingAfter",  str(bl, "spacingAfter"),  str(al, "spacingAfter"),  out);
    }

    private void diffLayout(DocumentComponent before, DocumentComponent after, List<LayoutDiffEntry> out) {
        LayoutProps bl = before.getLayoutProps();
        LayoutProps al = after.getLayoutProps();

        // Heading level
        Integer oldHl = bl != null ? bl.getHeadingLevel() : null;
        Integer newHl = al != null ? al.getHeadingLevel() : null;
        if (!Objects.equals(oldHl, newHl)) {
            out.add(LayoutDiffEntry.builder().blockId(after.getId()).changeType("HEADING_LEVEL")
                .oldValue(oldHl != null ? oldHl.toString() : null)
                .newValue(newHl != null ? newHl.toString() : null).build());
        }

        // List level
        Integer oldLl = bl != null ? bl.getListLevel() : null;
        Integer newLl = al != null ? al.getListLevel() : null;
        if (!Objects.equals(oldLl, newLl)) {
            out.add(LayoutDiffEntry.builder().blockId(after.getId()).changeType("LIST_LEVEL")
                .oldValue(oldLl != null ? oldLl.toString() : null)
                .newValue(newLl != null ? newLl.toString() : null).build());
        }

        // Component type changes (e.g. paragraph → heading)
        if (before.getType() != after.getType()) {
            out.add(LayoutDiffEntry.builder().blockId(after.getId()).changeType("TYPE_CHANGED")
                .oldValue(before.getType() != null ? before.getType().name() : null)
                .newValue(after.getType() != null ? after.getType().name() : null).build());
        }

        // Table row count change (detected at table level)
        if (after.getType() == ComponentType.TABLE && before.getType() == ComponentType.TABLE) {
            int oldRows = before.getChildren() != null ? before.getChildren().size() : 0;
            int newRows = after.getChildren()  != null ? after.getChildren().size()  : 0;
            if (oldRows != newRows) {
                out.add(LayoutDiffEntry.builder().blockId(after.getId())
                    .changeType(newRows > oldRows ? "TABLE_ROW_ADDED" : "TABLE_ROW_DELETED")
                    .oldValue(String.valueOf(oldRows))
                    .newValue(String.valueOf(newRows)).build());
            }
        }
    }

    // -----------------------------------------------------------------------
    //  String extraction helpers
    // -----------------------------------------------------------------------

    private String textOf(DocumentComponent c) {
        return c.getContentProps() != null ? c.getContentProps().getText() : null;
    }

    private String styleId(DocumentComponent c) {
        StyleRef sr = c.getStyleRef();
        return sr != null ? sr.getStyleId() : null;
    }

    private void compareStyleProp(String blockId, String prop, String oldVal, String newVal, List<StyleDiffEntry> out) {
        if (!Objects.equals(oldVal, newVal)) {
            out.add(StyleDiffEntry.builder().blockId(blockId).property(prop).oldValue(oldVal).newValue(newVal).build());
        }
    }

    @SuppressWarnings("unchecked")
    private String str(LayoutProps lp, String prop) {
        if (lp == null) return null;
        return switch (prop) {
            case "bold"          -> lp.getBold() != null      ? lp.getBold().toString()          : null;
            case "italic"        -> lp.getItalic() != null    ? lp.getItalic().toString()        : null;
            case "fontAscii"     -> lp.getFontAscii();
            case "fontSizePt"    -> lp.getFontSizePt() != null ? lp.getFontSizePt().toString()   : null;
            case "color"         -> lp.getColor();
            case "alignment"     -> lp.getAlignment();
            case "spacingBefore" -> lp.getSpacingBefore() != null ? lp.getSpacingBefore().toString() : null;
            case "spacingAfter"  -> lp.getSpacingAfter()  != null ? lp.getSpacingAfter().toString()  : null;
            default              -> null;
        };
    }
}
