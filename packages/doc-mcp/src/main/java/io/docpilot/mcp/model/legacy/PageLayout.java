package io.docpilot.mcp.model.legacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageLayout {

    String orientation;
    String paperSize;

    Double widthPt;
    Double heightPt;

    Double marginTopPt;
    Double marginBottomPt;
    Double marginLeftPt;
    Double marginRightPt;
    Double marginHeaderPt;
    Double marginFooterPt;

    Integer columns;
}
