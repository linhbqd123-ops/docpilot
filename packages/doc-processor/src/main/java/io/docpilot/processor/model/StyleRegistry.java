package io.docpilot.processor.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Complete style registry extracted from a DOCX file.
 *
 * <p>This registry captures enough information to reconstruct the original
 * document's look-and-feel when converting HTML back to DOCX.
 *
 * <pre>Schema (mirrors plan spec):
 * {
 *   "docId": "uuid",
 *   "filename": "contract.docx",
 *   "styles": { "Heading1": { ... }, "Normal": { ... }, ... },
 *   "customStyles": [...],
 *   "pageLayout": { ... },
 *   "numberingDefinitions": [...],
 *   "defaultFontAscii": "Calibri",
 *   "defaultFontSizePt": 11.0
 * }
 * </pre>
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StyleRegistry {

    String docId;
    String filename;
    Instant extractedAt;

    /**
     * Map of styleId → StyleEntry.
     * Keys are OOXML style IDs (e.g., "Heading1", "Normal", "TableGrid").
     */
    Map<String, StyleEntry> styles;

    /** Styles not derived from any built-in style. */
    List<StyleEntry> customStyles;

    PageLayout pageLayout;

    /**
     * Raw numbering definitions as JSON-serialisable maps.
     * Each entry represents one abstractNum or num element.
     */
    List<Map<String, Object>> numberingDefinitions;

    // Document defaults
    String defaultFontAscii;
    Double defaultFontSizePt;
    String defaultFontColor;
}
