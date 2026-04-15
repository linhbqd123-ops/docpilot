package io.docpilot.mcp.personalization;

public record DocumentSemanticChunk(
    String pointId,
    String sessionId,
    String blockId,
    String type,
    String logicalPath,
    String headingPath,
    String text,
    String indexedText
) {
}