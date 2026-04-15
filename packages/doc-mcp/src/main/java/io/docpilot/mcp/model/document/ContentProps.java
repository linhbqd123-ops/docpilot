package io.docpilot.mcp.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** Text / content properties for leaf nodes (TEXT_RUN, HYPERLINK, IMAGE …). */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentProps {

    /** Plain text content (for runs, paragraphs, headings). */
    String text;

    /** For IMAGE nodes – base64-encoded bytes. */
    String imageBase64;
    String imageMimeType;
    Integer imageWidthEmu;
    Integer imageHeightEmu;
    String imageAltText;

    /** For HYPERLINK nodes. */
    String hyperlinkUrl;
    String hyperlinkText;

    /** For FIELD nodes – field instruction string (e.g. "TOC"). */
    String fieldInstruction;
    String fieldResult;

    /** For TABLE_CELL – whether the cell is vertically merged with its neighbours. */
    Boolean cellMergedVertically;
    Boolean cellMergedHorizontally;
}
