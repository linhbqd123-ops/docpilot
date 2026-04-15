package io.docpilot.mcp.engine.validation;

import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.session.DocumentSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates the structural and style integrity of a document session.
 */
@Service
@Slf4j
public class ValidationService {

    public record ValidationResult(boolean ok, List<String> errors, List<String> warnings) {}

    /**
     * Validates the full document structure.
     * Checks heading hierarchy, section structure, and component tree consistency.
     */
    public ValidationResult validateStructure(DocumentSession session) {
        List<String> errors   = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (session.getRoot() == null) {
            errors.add("Session has no root component");
            return new ValidationResult(false, errors, warnings);
        }

        if (session.getRoot().getType() != ComponentType.DOCUMENT) {
            errors.add("Root component must be DOCUMENT type, found: " + session.getRoot().getType());
        }

        validateTree(session.getRoot(), null, errors, warnings, new int[]{0});

        // Heading hierarchy check
        validateHeadingHierarchy(session.getRoot(), errors, warnings);

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * Validates style consistency — checks that all styleRefs referenced by components
     * exist in the provided style IDs set.
     */
    public ValidationResult validateStyles(DocumentSession session, java.util.Set<String> knownStyleIds) {
        List<String> errors   = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        validateStylesRecursive(session.getRoot(), knownStyleIds, warnings);
        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * Checks whether a patch would be a "breaking change" — i.e., it modifies
     * a heading that other content depends on, or changes a style definition used by
     * many blocks.
     */
    public ValidationResult checkBreakingChange(DocumentSession session, List<String> affectedBlockIds) {
        List<String> errors   = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        long headingsModified = affectedBlockIds.stream()
            .map(id -> findById(session.getRoot(), id))
            .filter(c -> c != null && c.getType() == ComponentType.HEADING)
            .count();

        if (headingsModified > 0) {
            warnings.add(headingsModified + " heading(s) will be modified — this may affect the document outline and TOC.");
        }

        double ratio = session.getParagraphCount() > 0
            ? (double) affectedBlockIds.size() / session.getParagraphCount() : 0;
        if (ratio > 0.3) {
            warnings.add(String.format("Patch affects %.0f%% of the document — please review carefully.", ratio * 100));
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    // -----------------------------------------------------------------------
    //  Tree validation
    // -----------------------------------------------------------------------

    private void validateTree(DocumentComponent node,
                               String expectedParentId,
                               List<String> errors,
                               List<String> warnings,
                               int[] nodeCount) {
        if (node == null) return;
        nodeCount[0]++;

        if (node.getId() == null) {
            errors.add("Found a component with null id (parent=" + expectedParentId + ")");
        }
        if (node.getType() == null) {
            errors.add("Component " + node.getId() + " has null type");
        }
        if (expectedParentId != null && !expectedParentId.equals(node.getParentId())) {
            warnings.add("Component " + node.getId() + " parentId mismatch: expected " + expectedParentId + " but found " + node.getParentId());
        }
        if (node.getAnchor() == null) {
            warnings.add("Component " + node.getId() + " is missing an anchor");
        }

        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                validateTree(child, node.getId(), errors, warnings, nodeCount);
            }
        }
    }

    private void validateHeadingHierarchy(DocumentComponent root, List<String> errors, List<String> warnings) {
        int[] lastLevel = {0};
        walkHeadings(root, lastLevel, warnings);
    }

    private void walkHeadings(DocumentComponent node, int[] lastLevel, List<String> warnings) {
        if (node == null) return;
        if (node.getType() == ComponentType.HEADING && node.getLayoutProps() != null) {
            Integer level = node.getLayoutProps().getHeadingLevel();
            if (level != null && lastLevel[0] > 0 && level > lastLevel[0] + 1) {
                warnings.add("Heading hierarchy skip: jumped from H" + lastLevel[0] + " to H" + level + " at block " + node.getId());
            }
            if (level != null) lastLevel[0] = level;
        }
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                walkHeadings(child, lastLevel, warnings);
            }
        }
    }

    private void validateStylesRecursive(DocumentComponent node, java.util.Set<String> knownIds, List<String> warnings) {
        if (node == null) return;
        if (node.getStyleRef() != null && node.getStyleRef().getStyleId() != null) {
            if (!knownIds.contains(node.getStyleRef().getStyleId())) {
                warnings.add("Component " + node.getId() + " references unknown style: " + node.getStyleRef().getStyleId());
            }
        }
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                validateStylesRecursive(child, knownIds, warnings);
            }
        }
    }

    private DocumentComponent findById(DocumentComponent node, String id) {
        if (node == null) return null;
        if (id.equals(node.getId())) return node;
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                DocumentComponent found = findById(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }
}
