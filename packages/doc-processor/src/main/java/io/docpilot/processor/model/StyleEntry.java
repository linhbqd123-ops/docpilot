package io.docpilot.processor.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/**
 * Represents a single OOXML style entry with all relevant typographic properties.
 * All fields are nullable — only properties explicitly set in the DOCX are populated.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StyleEntry {

    /** Style type: "paragraph", "character", "table", "numbering" */
    String type;

    /** Human-readable name (e.g., "Heading 1"). */
    String name;

    /** Parent style ID (for inheritance). */
    String basedOn;

    // ---- Run (character) properties ----
    String fontAscii;
    String fontEastAsia;
    Double sizePt;         // font size in points
    Boolean bold;
    Boolean italic;
    Boolean underline;
    Boolean strikethrough;
    /** Hex RGB, e.g. "1F4E79" or "auto". */
    String color;
    String highlight;
    Double characterSpacingPt;

    // ---- Paragraph properties ----
    /** "left" | "center" | "right" | "both" (justified). */
    String alignment;
    Double spacingBeforePt;
    Double spacingAfterPt;
    Double lineSpacingPt;
    Double indentLeftPt;
    Double indentRightPt;
    Double indentHangingPt;
    Double indentFirstLinePt;

    // ---- Table cell shading ----
    /** Background fill hex colour (for table styles). */
    String bgColor;

    // ---- Borders ----
    Map<String, String> borders;  // key: "top","bottom","left","right" → value: "single", "double"…
}
