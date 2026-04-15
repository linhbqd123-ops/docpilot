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
    CHANGE_LIST_LEVEL
}
