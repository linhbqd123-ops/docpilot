package io.docpilot.mcp.model.patch;

/**
 * Atomic operation types for the patch engine.
 * Each type maps to one execute/validate/undo triple in PatchEngine.
 */
public enum OperationType {
    // ── Text / content ──
    REPLACE_TEXT_RANGE,
    INSERT_TEXT_AT,
    DELETE_TEXT_RANGE,
    NORMALIZE_TEXT_RUNS,

    // ── Block structure ──
    CREATE_BLOCK,
    DELETE_BLOCK,
    MOVE_BLOCK,
    CLONE_BLOCK,

    // ── Table ──
    UPDATE_CELL_CONTENT,
    INSERT_ROW,
    DELETE_ROW,
    INSERT_COLUMN,
    DELETE_COLUMN,

    // ── Style ──
    APPLY_STYLE,
    APPLY_INLINE_FORMAT,
    SET_HEADING_LEVEL,
    NORMALIZE_STYLE,
    CREATE_STYLE,
    UPDATE_STYLE_DEFINITION,

    // ── List ──
    CHANGE_LIST_TYPE,
    CHANGE_LIST_LEVEL,

    // ── Line-based (simplified AI operations) ───────────────────────────────
    /**
     * Replace the text content of a single block element (identified by line_number),
     * keeping its outer HTML wrapper, class, and style attributes intact.
     * value: {line_number, old_text, new_text}
     */
    REPLACE_TEXT_LINE,

    /**
     * Replace an entire block element (identified by line_number) with
     * AI-provided HTML markup.
     * value: {line_number, html}
     */
    REPLACE_BLOCK_LINE
}
