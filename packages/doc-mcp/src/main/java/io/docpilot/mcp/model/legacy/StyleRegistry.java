package io.docpilot.mcp.model.legacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StyleRegistry {

    String docId;
    String filename;
    Instant extractedAt;

    /** Map of styleId → StyleEntry. */
    Map<String, StyleEntry> styles;

    List<StyleEntry> customStyles;
    PageLayout pageLayout;
    List<Map<String, Object>> numberingDefinitions;

    String defaultFontAscii;
    Double defaultFontSizePt;
    String defaultFontColor;
}
