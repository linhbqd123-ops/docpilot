package io.docpilot.mcp.engine.projection;

import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.document.LayoutProps;
import io.docpilot.mcp.model.session.DocumentSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Renders a {@link DocumentSession}'s component tree into an annotated HTML projection.
 *
 * <p>Every rendered element carries data attributes that allow the frontend editor to
 * map DOM mutations back to document components (plan section 8.3):
 * <ul>
 *   <li>{@code data-doc-node-id} — stableId of the component</li>
 *   <li>{@code data-doc-node-type} — component type</li>
 *   <li>{@code data-style-ref} — OOXML style ID</li>
 *   <li>{@code data-logical-path} — three-layer anchor logical path</li>
 *   <li>{@code data-anchor} — full stableId (alias for data-doc-node-id)</li>
 * </ul>
 *
 * <p>This projection is <em>read-only truth for the UI</em>. The frontend must never
 * commit {@code innerHTML} directly; it must translate DOM deltas into patch operations
 * and submit them to {@code /api/sessions/{sessionId}/patches}.
 */
@Service
@Slf4j
public class HtmlProjectionService {

    /**
     * Renders the full document session to an annotated HTML string.
     */
    public String project(DocumentSession session) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
            + "<style>").append(baseStyles()).append("</style></head><body ");
        appendDataAttrs(html, session.getRoot());
        html.append(">\n");

        renderChildren(session.getRoot().getChildren(), html, 0);

        html.append("</body></html>");
        return html.toString();
    }

    /**
     * Renders only the HTML body fragment (no {@code <html>/<head>} wrapper).
     * Suitable for embedding in an existing editor container.
     */
    public String projectFragment(DocumentSession session) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"doc-canvas\" ");
        appendDataAttrs(html, session.getRoot());
        html.append(">\n");
        renderChildren(session.getRoot().getChildren(), html, 0);
        html.append("</div>");
        return html.toString();
    }

    // -----------------------------------------------------------------------
    //  Recursive rendering
    // -----------------------------------------------------------------------

    private void renderChildren(List<DocumentComponent> children, StringBuilder html, int depth) {
        if (children == null) return;
        for (int index = 0; index < children.size(); ) {
            DocumentComponent child = children.get(index);
            if (child.getType() == ComponentType.LIST_ITEM) {
                index = renderListSequence(children, index, html, depth);
                continue;
            }
            renderComponent(child, html, depth);
            index++;
        }
    }

    private int renderListSequence(List<DocumentComponent> children, int startIndex, StringBuilder html, int depth) {
        DocumentComponent first = children.get(startIndex);
        int level = listLevel(first);
        String listTag = listTag(first);

        html.append("<").append(listTag).append(">\n");

        int index = startIndex;
        while (index < children.size()) {
            DocumentComponent child = children.get(index);
            if (child.getType() != ComponentType.LIST_ITEM) {
                break;
            }

            int childLevel = listLevel(child);
            if (childLevel < level) {
                break;
            }
            if (childLevel > level) {
                index = renderListSequence(children, index, html, depth + 1);
                continue;
            }
            if (!listTag.equals(listTag(child)) && index > startIndex) {
                break;
            }

            html.append("<li ").append(dataAttrs(child))
                .append(" data-list-level=\"").append(level).append("\"")
                .append(inlineStyle(child)).append(">");
            renderListItemContent(child, html, depth + 1);
            index++;

            while (index < children.size()) {
                DocumentComponent next = children.get(index);
                if (next.getType() != ComponentType.LIST_ITEM || listLevel(next) <= level) {
                    break;
                }
                index = renderListSequence(children, index, html, depth + 1);
            }

            html.append("</li>\n");

            if (index < children.size()) {
                DocumentComponent next = children.get(index);
                if (next.getType() == ComponentType.LIST_ITEM
                    && listLevel(next) == level
                    && !listTag.equals(listTag(next))) {
                    break;
                }
            }
        }

        html.append("</").append(listTag).append(">\n");
        return index;
    }

    private void renderComponent(DocumentComponent c, StringBuilder html, int depth) {
        ComponentType type = c.getType();
        if (type == null) return;

        switch (type) {
            case SECTION       -> renderSection(c, html, depth);
            case HEADING       -> renderHeading(c, html, depth);
            case PARAGRAPH     -> renderParagraph(c, html, depth);
            case LIST_ITEM     -> renderListItem(c, html, depth);
            case TABLE         -> renderTable(c, html, depth);
            case TABLE_ROW     -> renderTableRow(c, html, depth);
            case TABLE_CELL    -> renderTableCell(c, html, depth);
            case IMAGE         -> renderImage(c, html, depth);
            case HYPERLINK     -> renderHyperlink(c, html, depth);
            case PAGE_BREAK    -> html.append("<div class=\"page-break\" ").append(dataAttrs(c)).append("></div>\n");
            case SECTION_BREAK -> html.append("<hr class=\"section-break\" ").append(dataAttrs(c)).append("/>\n");
            case TEXT_RUN      -> renderRun(c, html);
            default            -> renderParagraph(c, html, depth); // safe fallback
        }
    }

    private void renderSection(DocumentComponent c, StringBuilder html, int depth) {
        html.append("<section ").append(dataAttrs(c)).append(">\n");
        renderChildren(c.getChildren(), html, depth + 1);
        html.append("</section>\n");
    }

    private void renderHeading(DocumentComponent c, StringBuilder html, int depth) {
        int level = 1;
        if (c.getLayoutProps() != null && c.getLayoutProps().getHeadingLevel() != null) {
            level = Math.min(6, Math.max(1, c.getLayoutProps().getHeadingLevel()));
        }
        String tag = "h" + level;
        html.append("<").append(tag).append(" ").append(dataAttrs(c))
            .append(inlineStyle(c)).append(">");
        appendTextContent(c, html);
        html.append("</").append(tag).append(">\n");
    }

    private void renderParagraph(DocumentComponent c, StringBuilder html, int depth) {
        html.append("<p ").append(dataAttrs(c)).append(inlineStyle(c)).append(">");
        // If children are runs, render them; otherwise fall back to contentProps.text
        if (hasRunChildren(c)) {
            renderChildren(c.getChildren(), html, depth + 1);
        } else {
            appendTextContent(c, html);
        }
        html.append("</p>\n");
    }

    private void renderListItem(DocumentComponent c, StringBuilder html, int depth) {
        int level = 0;
        if (c.getLayoutProps() != null && c.getLayoutProps().getListLevel() != null) {
            level = c.getLayoutProps().getListLevel();
        }
        html.append("<li ").append(dataAttrs(c))
            .append(" data-list-level=\"").append(level).append("\"")
            .append(inlineStyle(c)).append(">");
        if (hasRunChildren(c)) {
            renderChildren(c.getChildren(), html, depth + 1);
        } else {
            appendTextContent(c, html);
        }
        html.append("</li>\n");
    }

    private void renderListItemContent(DocumentComponent c, StringBuilder html, int depth) {
        if (hasRunChildren(c)) {
            renderChildren(c.getChildren(), html, depth + 1);
            return;
        }

        if (c.getChildren() != null && !c.getChildren().isEmpty()) {
            renderChildren(c.getChildren(), html, depth + 1);
            return;
        }

        appendTextContent(c, html);
    }

    private void renderTable(DocumentComponent c, StringBuilder html, int depth) {
        html.append("<table ").append(dataAttrs(c)).append(">\n<tbody>\n");
        renderChildren(c.getChildren(), html, depth + 1);
        html.append("</tbody>\n</table>\n");
    }

    private void renderTableRow(DocumentComponent c, StringBuilder html, int depth) {
        html.append("<tr ").append(dataAttrs(c)).append(">\n");
        renderChildren(c.getChildren(), html, depth + 1);
        html.append("</tr>\n");
    }

    private void renderTableCell(DocumentComponent c, StringBuilder html, int depth) {
        html.append("<td ").append(dataAttrs(c)).append(">\n");
        renderChildren(c.getChildren(), html, depth + 1);
        html.append("</td>\n");
    }

    private void renderImage(DocumentComponent c, StringBuilder html, int depth) {
        String base64 = c.getContentProps() != null ? c.getContentProps().getImageBase64() : null;
        String mime   = c.getContentProps() != null ? c.getContentProps().getImageMimeType() : null;
        String alt    = c.getContentProps() != null ? c.getContentProps().getImageAltText() : "";
        if (base64 != null && mime != null) {
            html.append("<img ").append(dataAttrs(c))
                .append(" src=\"data:").append(mime).append(";base64,").append(base64).append("\"")
                .append(" alt=\"").append(escapeHtml(alt != null ? alt : "")).append("\"")
                .append("/>\n");
        } else {
            html.append("<div class=\"img-placeholder\" ").append(dataAttrs(c)).append(">📷</div>\n");
        }
    }

    private void renderHyperlink(DocumentComponent c, StringBuilder html, int depth) {
        String url  = c.getContentProps() != null ? c.getContentProps().getHyperlinkUrl() : "#";
        String text = c.getContentProps() != null ? c.getContentProps().getHyperlinkText() : "";
        html.append("<a href=\"").append(escapeHtml(url != null ? url : "#")).append("\" ")
            .append(dataAttrs(c)).append(">")
            .append(escapeHtml(text != null ? text : "")).append("</a>");
    }

    private void renderRun(DocumentComponent c, StringBuilder html) {
        String text = c.getContentProps() != null ? c.getContentProps().getText() : "";
        if (text == null || text.isEmpty()) return;

        LayoutProps lp = c.getLayoutProps();
        boolean bold      = lp != null && Boolean.TRUE.equals(lp.getBold());
        boolean italic    = lp != null && Boolean.TRUE.equals(lp.getItalic());
        boolean underline = lp != null && Boolean.TRUE.equals(lp.getUnderline());
        boolean strike    = lp != null && Boolean.TRUE.equals(lp.getStrikethrough());

        StringBuilder openTags  = new StringBuilder();
        StringBuilder closeTags = new StringBuilder();
        if (bold)      { openTags.append("<strong>"); closeTags.insert(0, "</strong>"); }
        if (italic)    { openTags.append("<em>");     closeTags.insert(0, "</em>"); }
        if (underline) { openTags.append("<u>");      closeTags.insert(0, "</u>"); }
        if (strike)    { openTags.append("<s>");      closeTags.insert(0, "</s>"); }

        html.append("<span ").append(dataAttrs(c)).append(runStyle(lp)).append(">")
            .append(openTags)
            .append(escapeHtml(text))
            .append(closeTags)
            .append("</span>");
    }

    // -----------------------------------------------------------------------
    //  Data attributes
    // -----------------------------------------------------------------------

    private String dataAttrs(DocumentComponent c) {
        StringBuilder sb = new StringBuilder();
        if (c.getId() != null) {
            sb.append("data-doc-node-id=\"").append(c.getId()).append("\" ");
            sb.append("data-anchor=\"").append(c.getId()).append("\" ");
        }
        if (c.getType() != null) {
            sb.append("data-doc-node-type=\"").append(c.getType().name().toLowerCase()).append("\" ");
        }
        if (c.getStyleRef() != null && c.getStyleRef().getStyleId() != null) {
            sb.append("data-style-ref=\"").append(c.getStyleRef().getStyleId()).append("\" ");
        }
        if (c.getAnchor() != null && c.getAnchor().getLogicalPath() != null) {
            sb.append("data-logical-path=\"").append(c.getAnchor().getLogicalPath()).append("\" ");
        }
        return sb.toString().trim();
    }

    private void appendDataAttrs(StringBuilder html, DocumentComponent c) {
        html.append(dataAttrs(c));
    }

    // -----------------------------------------------------------------------
    //  Inline style helpers
    // -----------------------------------------------------------------------

    private String inlineStyle(DocumentComponent c) {
        if (c.getLayoutProps() == null) return "";
        LayoutProps lp = c.getLayoutProps();
        StringBuilder sb = new StringBuilder();

        if (lp.getAlignment() != null) sb.append("text-align:").append(lp.getAlignment().toLowerCase()).append(";");
        if (lp.getSpacingBefore() != null) sb.append("margin-top:").append(lp.getSpacingBefore() / 20).append("pt;");
        if (lp.getSpacingAfter() != null)  sb.append("margin-bottom:").append(lp.getSpacingAfter() / 20).append("pt;");
        if (lp.getIndentLeft() != null)    sb.append("margin-left:").append(lp.getIndentLeft() / 20).append("pt;");

        if (sb.isEmpty()) return "";
        return " style=\"" + sb + "\"";
    }

    private String runStyle(LayoutProps lp) {
        if (lp == null) return "";
        StringBuilder sb = new StringBuilder();
        if (lp.getFontAscii() != null) sb.append("font-family:'").append(lp.getFontAscii()).append("';");
        if (lp.getFontSizePt() != null) sb.append("font-size:").append(lp.getFontSizePt()).append("pt;");
        if (lp.getColor() != null && !"auto".equalsIgnoreCase(lp.getColor())) sb.append("color:#").append(lp.getColor()).append(";");
        if (sb.isEmpty()) return "";
        return " style=\"" + sb + "\"";
    }

    // -----------------------------------------------------------------------
    //  Text helpers
    // -----------------------------------------------------------------------

    private void appendTextContent(DocumentComponent c, StringBuilder html) {
        if (c.getContentProps() != null && c.getContentProps().getText() != null) {
            html.append(escapeHtml(c.getContentProps().getText()));
        }
    }

    private boolean hasRunChildren(DocumentComponent c) {
        return c.getChildren() != null
            && !c.getChildren().isEmpty()
            && c.getChildren().stream().anyMatch(ch -> ch.getType() == ComponentType.TEXT_RUN);
    }

    private int listLevel(DocumentComponent component) {
        if (component.getLayoutProps() == null || component.getLayoutProps().getListLevel() == null) {
            return 0;
        }
        return Math.max(0, component.getLayoutProps().getListLevel());
    }

    private String listTag(DocumentComponent component) {
        if (component.getLayoutProps() == null || component.getLayoutProps().getListType() == null) {
            return "ul";
        }

        String listType = component.getLayoutProps().getListType().toUpperCase();
        return switch (listType) {
            case "DECIMAL", "ALPHA_UPPER", "ALPHA_LOWER", "ROMAN_UPPER", "ROMAN_LOWER" -> "ol";
            default -> "ul";
        };
    }

    private String escapeHtml(String text) {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    // -----------------------------------------------------------------------
    //  Base CSS
    // -----------------------------------------------------------------------

    private String baseStyles() {
        return """
            body { font-family: Calibri, sans-serif; font-size: 11pt; line-height: 1.4; max-width: 900px; margin: 0 auto; padding: 24px; }
            h1,h2,h3,h4,h5,h6 { margin-top: 0.75em; margin-bottom: 0.25em; }
            p { margin: 0 0 6pt 0; }
            table { border-collapse: collapse; width: 100%; margin: 8pt 0; }
            td, th { border: 1px solid #ccc; padding: 4pt 6pt; vertical-align: top; }
            .page-break { page-break-after: always; border-top: 2px dashed #aaa; margin: 12pt 0; }
            .section-break { border: none; border-top: 1px dashed #aaa; margin: 8pt 0; }
            .img-placeholder { background: #f0f0f0; padding: 16px; text-align: center; color: #888; }
            [data-doc-node-type] { outline: none; }
            [data-doc-node-type]:focus { outline: 2px solid #3b82f6; border-radius: 2px; }
            """;
    }
}
