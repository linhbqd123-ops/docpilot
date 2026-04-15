package io.docpilot.mcp.model.diff;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * Complete document diff between two revisions.
 * Carries three separate diff layers: text, style, and layout.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentDiff {

    String baseRevisionId;
    String targetRevisionId;
    String sessionId;

    List<TextDiffEntry> textDiffs;
    List<StyleDiffEntry> styleDiffs;
    List<LayoutDiffEntry> layoutDiffs;

    // ── Summary ──
    int textEditCount;
    int styleEditCount;
    int layoutEditCount;
    boolean hasConflicts;
}
