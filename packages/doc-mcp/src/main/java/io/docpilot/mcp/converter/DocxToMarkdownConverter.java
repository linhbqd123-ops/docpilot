package io.docpilot.mcp.converter;

import io.docpilot.mcp.exception.ConversionException;
import lombok.extern.slf4j.Slf4j;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.*;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Converts a DOCX file to Markdown text.
 *
 * <p>Custom implementation that walks the OOXML document tree because there is
 * no production-ready Java library for DOCX â†’ Markdown with adequate fidelity.
 *
 * <p>Supported:
 * <ul>
 *   <li>Heading 1â€“6 (ATX style: {@code #} â€¦ {@code ######})</li>
 *   <li>Bold / italic / bold-italic inline runs</li>
 *   <li>Unordered and ordered lists (single level)</li>
 *   <li>Tables (GFM pipe tables)</li>
 *   <li>Hyperlinks (Markdown link syntax)</li>
 *   <li>Horizontal rules (from semantic paragraph styles "HorizontalRule")</li>
 *   <li>Plain paragraphs</li>
 * </ul>
 */
@Component
@Slf4j
public class DocxToMarkdownConverter {

    public String convert(InputStream in, String filename) {
        try {
            WordprocessingMLPackage wml = WordprocessingMLPackage.load(in);
            StringBuilder md = new StringBuilder();
            Body body = wml.getMainDocumentPart().getJaxbElement().getBody();

            int listCounterOl = 0;  // simple ordered-list counter
            String prevStyle = null;

            for (Object obj : body.getContent()) {
                Object unwrapped = XmlUtils.unwrap(obj);

                if (unwrapped instanceof P para) {
                    String styleId = getStyleId(para);
                    String text = extractText(para);

                    if (text.isBlank() && !"HorizontalLine".equals(styleId)) {
                        md.append("\n");
                        listCounterOl = 0;
                        prevStyle = null;
                        continue;
                    }

                    if (styleId != null && styleId.startsWith("Heading")) {
                        int level = headingLevel(styleId);
                        md.append("#".repeat(level)).append(" ").append(text).append("\n\n");
                        listCounterOl = 0;

                    } else if ("ListParagraph".equals(styleId) || isListParagraph(para)) {
                        boolean isOrdered = isOrderedList(para);
                        if (isOrdered) {
                            listCounterOl++;
                            md.append(listCounterOl).append(". ").append(text).append("\n");
                        } else {
                            listCounterOl = 0;
                            md.append("- ").append(text).append("\n");
                        }

                    } else if ("HorizontalLine".equals(styleId)) {
                        md.append("\n---\n\n");
                        listCounterOl = 0;

                    } else {
                        // Normal paragraph â€” extract inline formatting
                        String inlineMarkdown = extractInlineMarkdown(para);
                        md.append(inlineMarkdown).append("\n\n");
                        listCounterOl = 0;
                    }
                    prevStyle = styleId;

                } else if (unwrapped instanceof Tbl table) {
                    md.append(tableToMarkdown(table)).append("\n");
                    listCounterOl = 0;
                }
            }

            return md.toString().stripTrailing() + "\n";

        } catch (Exception e) {
            throw new ConversionException("Failed to convert DOCX to Markdown: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    //  Paragraph helpers
    // -----------------------------------------------------------------------

    private String getStyleId(P para) {
        if (para.getPPr() == null || para.getPPr().getPStyle() == null) return null;
        return para.getPPr().getPStyle().getVal();
    }

    private int headingLevel(String styleId) {
        // "Heading1" â†’ 1, "Heading2" â†’ 2 â€¦ "heading1" too
        try {
            return Integer.parseInt(styleId.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private boolean isListParagraph(P para) {
        return para.getPPr() != null && para.getPPr().getNumPr() != null;
    }

    private boolean isOrderedList(P para) {
        // A simple heuristic: text starts with a digit followed by ) or .
        String text = extractText(para).trim();
        return text.matches("^\\d+[.)].+");
    }

    // -----------------------------------------------------------------------
    //  Text extraction â€” plain
    // -----------------------------------------------------------------------

    private String extractText(P para) {
        StringBuilder sb = new StringBuilder();
        for (Object obj : para.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof R run) {
                for (Object c : run.getContent()) {
                    Object uwc = XmlUtils.unwrap(c);
                    if (uwc instanceof Text t) sb.append(t.getValue());
                    if (uwc instanceof Br) sb.append(" ");
                }
            } else if (uw instanceof P.Hyperlink link) {
                sb.append(extractHyperlinkText(link));
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    //  Text extraction â€” with inline Markdown formatting
    // -----------------------------------------------------------------------

    private String extractInlineMarkdown(P para) {
        StringBuilder sb = new StringBuilder();
        for (Object obj : para.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof R run) {
                sb.append(runToMarkdown(run));
            } else if (uw instanceof P.Hyperlink link) {
                sb.append(hyperlinkToMarkdown(link));
            }
        }
        return sb.toString().strip();
    }

    private String runToMarkdown(R run) {
        StringBuilder text = new StringBuilder();
        for (Object c : run.getContent()) {
            Object uwc = XmlUtils.unwrap(c);
            if (uwc instanceof Text t) text.append(t.getValue());
            if (uwc instanceof Br) text.append("  \n"); // Markdown line break
        }
        if (text.isEmpty()) return "";

        RPr rpr = run.getRPr();
        boolean bold   = rpr != null && rpr.getB() != null && rpr.getB().isVal();
        boolean italic = rpr != null && rpr.getI() != null && rpr.getI().isVal();
        boolean code   = rpr != null && rpr.getRStyle() != null
                         && "VerbatimChar".equals(rpr.getRStyle().getVal());

        String result = text.toString();
        if (code)   result = "`" + result + "`";
        if (bold && italic) result = "***" + result + "***";
        else if (bold)      result = "**"  + result + "**";
        else if (italic)    result = "*"   + result + "*";
        return result;
    }

    private String hyperlinkToMarkdown(P.Hyperlink link) {
        String text = extractHyperlinkText(link);
        String url  = link.getId() != null ? link.getId() : "";
        return url.isEmpty() ? text : "[" + text + "](" + url + ")";
    }

    private String extractHyperlinkText(P.Hyperlink link) {
        StringBuilder sb = new StringBuilder();
        for (Object item : link.getContent()) {
            Object uw = XmlUtils.unwrap(item);
            if (uw instanceof R run) {
                for (Object c : run.getContent()) {
                    Object uwc = XmlUtils.unwrap(c);
                    if (uwc instanceof Text t) sb.append(t.getValue());
                }
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    //  Table â†’ Markdown (GFM pipe tables)
    // -----------------------------------------------------------------------

    private String tableToMarkdown(Tbl table) {
        StringBuilder sb = new StringBuilder();
        List<Object> rows = table.getContent();
        boolean headerWritten = false;

        for (Object rowObj : rows) {
            Object uw = XmlUtils.unwrap(rowObj);
            if (!(uw instanceof Tr row)) continue;

            List<String> cells = row.getContent().stream()
                .map(XmlUtils::unwrap)
                .filter(c -> c instanceof Tc)
                .map(c -> cellText((Tc) c))
                .toList();

            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");

            if (!headerWritten) {
                // separator row
                String sep = "| " + cells.stream().map(c -> "---").collect(java.util.stream.Collectors.joining(" | ")) + " |";
                sb.append(sep).append("\n");
                headerWritten = true;
            }
        }
        return sb.toString();
    }

    private String cellText(Tc tc) {
        StringBuilder sb = new StringBuilder();
        for (Object obj : tc.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof P para) sb.append(extractText(para));
        }
        // Pipes inside cells must be escaped in GFM
        return sb.toString().replace("|", "\\|").strip();
    }
}

