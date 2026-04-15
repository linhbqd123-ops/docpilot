package io.docpilot.mcp.personalization;

public record SemanticSearchMatch(
    String blockId,
    String type,
    String text,
    String logicalPath,
    String headingPath,
    double score
) {
}