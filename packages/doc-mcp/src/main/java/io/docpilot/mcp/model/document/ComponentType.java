package io.docpilot.mcp.model.document;

/**
 * All node types that can appear in the canonical component tree.
 * Mirrors the component model from the upgrade plan (section 8.2).
 */
public enum ComponentType {
    DOCUMENT,
    SECTION,
    PARAGRAPH,
    TEXT_RUN,
    HEADING,
    LIST,
    LIST_ITEM,
    TABLE,
    TABLE_ROW,
    TABLE_CELL,
    IMAGE,
    HYPERLINK,
    FIELD,
    COMMENT,
    BOOKMARK,
    HEADER,
    FOOTER,
    FOOTNOTE,
    PAGE_BREAK,
    SECTION_BREAK
}
