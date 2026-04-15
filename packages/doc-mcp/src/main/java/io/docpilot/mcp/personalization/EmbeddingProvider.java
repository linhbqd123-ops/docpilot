package io.docpilot.mcp.personalization;

import java.util.List;

interface EmbeddingProvider {

    String name();

    List<float[]> embed(List<String> inputs);
}