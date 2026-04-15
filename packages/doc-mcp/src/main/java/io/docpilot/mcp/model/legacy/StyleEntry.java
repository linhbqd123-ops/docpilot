package io.docpilot.mcp.model.legacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;
import java.util.Map;

@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StyleEntry {

    String type;
    String name;
    String basedOn;

    String fontAscii;
    String fontEastAsia;
    Double sizePt;
    Boolean bold;
    Boolean italic;
    Boolean underline;
    Boolean strikethrough;
    String color;
    String highlight;
    Double characterSpacingPt;

    String alignment;
    Double spacingBeforePt;
    Double spacingAfterPt;
    Double lineSpacingPt;
    Double indentLeftPt;
    Double indentRightPt;
    Double indentHangingPt;
    Double indentFirstLinePt;

    String bgColor;
    Map<String, String> borders;
}
