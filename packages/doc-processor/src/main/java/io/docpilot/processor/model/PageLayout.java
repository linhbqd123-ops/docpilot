package io.docpilot.processor.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Page layout extracted from the DOCX SectPr (section properties).
 * All measurements are in points (1 pt = 1/72 inch).
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageLayout {

    /** "portrait" or "landscape". */
    String orientation;

    /** Standard paper size name, e.g. "A4", "Letter". Can be null for custom sizes. */
    String paperSize;

    // Page dimensions (pt)
    Double widthPt;
    Double heightPt;

    // Margins (pt)
    Double marginTopPt;
    Double marginBottomPt;
    Double marginLeftPt;
    Double marginRightPt;
    Double marginHeaderPt;
    Double marginFooterPt;

    // Column count (1 = single-column)
    Integer columns;
}
