package io.docpilot.mcp.personalization;

import io.docpilot.mcp.model.session.DocumentSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnMissingBean(SemanticSearchService.class)
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