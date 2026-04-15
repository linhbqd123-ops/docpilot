package io.docpilot.mcp.personalization;

import io.docpilot.mcp.model.session.DocumentSession;

import java.util.List;

public interface SemanticSearchService {

    boolean isEnabled();

    String providerName();

    void reindexSession(DocumentSession session);

    List<SemanticSearchMatch> search(DocumentSession session, String query, int limit);
}