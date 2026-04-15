package io.docpilot.mcp.model.session;

/**
 * Lifecycle state of a {@link DocumentSession}.
 */
public enum SessionState {
    /** Session created but DOCX not yet fully imported. */
    IMPORTING,
    /** Fully loaded and ready for reads and edits. */
    READY,
    /** A patch is currently being applied. */
    APPLYING_PATCH,
    /** Export to DOCX in progress. */
    EXPORTING,
    /** Session has been closed / evicted. */
    CLOSED
}
