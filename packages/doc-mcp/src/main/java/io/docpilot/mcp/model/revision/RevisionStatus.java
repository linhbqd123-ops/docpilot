package io.docpilot.mcp.model.revision;

public enum RevisionStatus {
    /** Patch has been staged but not yet applied to the session. */
    PENDING,
    /** Patch has been applied. */
    APPLIED,
    /** Patch was rejected by the user or failed validation. */
    REJECTED,
    /** Concurrent edits created a conflict that requires manual resolution. */
    CONFLICT
}
