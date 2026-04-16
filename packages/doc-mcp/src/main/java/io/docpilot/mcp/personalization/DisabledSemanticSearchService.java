package io.docpilot.mcp.personalization;

import io.docpilot.mcp.model.session.DocumentSession;

import java.util.List;

public class DisabledSemanticSearchService implements SemanticSearchService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String providerName() {
        return "disabled";
    }

    @Override
    public void reindexSession(DocumentSession session) {
    }

    @Override
    public List<SemanticSearchMatch> search(DocumentSession session, String query, int limit) {
        return List.of();
    }
}