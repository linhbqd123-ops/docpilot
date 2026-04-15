package io.docpilot.mcp.model.diff;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** A structural / layout change (list level, table row/column, page break, etc.). */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LayoutDiffEntry {
    String blockId;
    /** "LIST_LEVEL" | "TABLE_ROW_ADDED" | "TABLE_ROW_DELETED" | "TABLE_CELL_MERGED"
     *  | "PAGE_BREAK_ADDED" | "PAGE_BREAK_DELETED" | "SECTION_BREAK_CHANGED"
     *  | "HEADING_LEVEL" | "PARAGRAPH_SPACING" | etc. */
    String changeType;
    String oldValue;
    String newValue;
}
