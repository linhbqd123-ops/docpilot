package io.docpilot.mcp.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** Layout / formatting properties for a component. */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LayoutProps {

    // ── Paragraph ──
    String alignment;       // LEFT | CENTER | RIGHT | JUSTIFY
    Integer indentLeft;     // twips
    Integer indentRight;
    Integer indentFirstLine;
    Integer spacingBefore;  // twips (20ths of a point)
    Integer spacingAfter;
    Integer lineSpacing;

    // ── Heading ──
    Integer headingLevel;   // 1–9

    // ── List ──
    String listType;        // BULLET | DECIMAL | ALPHA_UPPER | etc.
    Integer listLevel;      // 0-based depth

    // ── Table ──
    Integer tableWidth;     // twips
    String tableLayout;     // FIXED | AUTOFIT
    Integer colSpan;        // for TABLE_CELL
    Integer rowSpan;

    // ── Run / inline ──
    String fontAscii;
    String fontHAnsi;
    Double fontSizePt;
    Boolean bold;
    Boolean italic;
    Boolean underline;
    Boolean strikethrough;
    String color;           // hex, e.g. "FF0000"
    String highlightColor;

    // ── Page / section ──
    String pageBreakType;   // PAGE | COLUMN
    String sectionBreakType;// NEXT_PAGE | EVEN_PAGE | ODD_PAGE | CONTINUOUS
}
