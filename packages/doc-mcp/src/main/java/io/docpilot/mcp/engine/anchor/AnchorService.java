package io.docpilot.mcp.engine.anchor;

import io.docpilot.mcp.model.document.Anchor;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.DocumentComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Generates and resolves stable three-layer anchors for document components.
 *
 * <h3>Anchor layers (plan section 8.4)</h3>
 * <ol>
 *   <li>{@code stableId} — UUID assigned at import, never changes during session.</li>
 *   <li>{@code logicalPath} — structural position, e.g. {@code section[2]/table[1]/row[3]/cell[2]/paragraph[1]}.</li>
 *   <li>{@code structuralFingerprint} — SHA-256 of type+text+styleId+parentPath, truncated to 16 hex chars.</li>
 * </ol>
 *
 * <h3>Remap policy (plan section 8.4.1)</h3>
 * When locating a node after edits:
 * <ol>
 *   <li>Match by {@code stableId}.</li>
 *   <li>If not found, try {@code logicalPath}.</li>
 *   <li>If not found, try {@code structuralFingerprint}.</li>
 *   <li>If multiple candidates match the fingerprint, return a conflict instead of guessing.</li>
 * </ol>
 */
@Service
@Slf4j
public class AnchorService {

    // -----------------------------------------------------------------------
    //  Generate
    // -----------------------------------------------------------------------

    /**
     * Creates a new anchor for a component being imported for the first time.
     *
     * @param type         component type
     * @param logicalPath  pre-computed logical path (caller maintains position counters)
     * @param textExcerpt  first 100 chars of the component's text content (for fingerprint)
     * @param styleId      OOXML style ID (may be null)
     * @param parentPath   logical path of the parent (for fingerprint)
     * @return a fresh {@link Anchor}
     */
    public Anchor generate(ComponentType type,
                           String logicalPath,
                           String textExcerpt,
                           String styleId,
                           String parentPath) {
        String stableId = UUID.randomUUID().toString();
        String fingerprint = computeFingerprint(type, textExcerpt, styleId, parentPath);
        return Anchor.builder()
            .stableId(stableId)
            .logicalPath(logicalPath)
            .structuralFingerprint(fingerprint)
            .build();
    }

    /**
     * Creates an anchor for a TABLE_CELL with full table sub-anchors.
     */
    public Anchor generateCellAnchor(String logicalPath,
                                     String textExcerpt,
                                     String styleId,
                                     String parentPath,
                                     String tableId,
                                     String rowId,
                                     int rowIndex,
                                     int colIndex,
                                     String tableFingerprint) {
        String stableId = UUID.randomUUID().toString();
        String fingerprint = computeFingerprint(ComponentType.TABLE_CELL, textExcerpt, styleId, parentPath);
        String cellLogicalAddress = "R" + (rowIndex + 1) + "C" + (colIndex + 1);
        return Anchor.builder()
            .stableId(stableId)
            .logicalPath(logicalPath)
            .structuralFingerprint(fingerprint)
            .tableId(tableId)
            .rowId(rowId)
            .cellLogicalAddress(cellLogicalAddress)
            .tableFingerprint(tableFingerprint)
            .build();
    }

    // -----------------------------------------------------------------------
    //  Resolve / Remap
    // -----------------------------------------------------------------------

    /**
     * Locates a component in the tree using the three-layer remap policy.
     *
     * @param anchor        the anchor to resolve
     * @param componentTree flat lookup index built by {@link #buildIndex(DocumentComponent)}
     * @return the resolved component, or empty if not found
     */
    public Optional<DocumentComponent> resolve(Anchor anchor,
                                               Map<String, DocumentComponent> componentTree) {
        // Layer 1: stableId
        DocumentComponent byId = componentTree.get(anchor.getStableId());
        if (byId != null) return Optional.of(byId);

        // Layer 2: logicalPath
        if (anchor.getLogicalPath() != null) {
            Optional<DocumentComponent> byPath = componentTree.values().stream()
                .filter(c -> c.getAnchor() != null
                    && anchor.getLogicalPath().equals(c.getAnchor().getLogicalPath()))
                .findFirst();
            if (byPath.isPresent()) {
                log.debug("Anchor resolved via logicalPath: {}", anchor.getLogicalPath());
                return byPath;
            }
        }

        // Layer 3: structuralFingerprint — must be unique to avoid guessing
        if (anchor.getStructuralFingerprint() != null) {
            List<DocumentComponent> candidates = componentTree.values().stream()
                .filter(c -> c.getAnchor() != null
                    && anchor.getStructuralFingerprint().equals(c.getAnchor().getStructuralFingerprint()))
                .toList();
            if (candidates.size() == 1) {
                log.debug("Anchor resolved via structuralFingerprint: {}", anchor.getStructuralFingerprint());
                return Optional.of(candidates.get(0));
            }
            if (candidates.size() > 1) {
                log.warn("Anchor fingerprint {} matched {} candidates — returning conflict instead of guessing.",
                    anchor.getStructuralFingerprint(), candidates.size());
            }
        }

        return Optional.empty();
    }

    /**
     * Resolves a TABLE_CELL anchor with table-specific fallback (cellLogicalAddress + tableFingerprint).
     */
    public Optional<DocumentComponent> resolveCellAnchor(Anchor anchor,
                                                          Map<String, DocumentComponent> componentTree) {
        // Primary: standard three-layer resolution
        Optional<DocumentComponent> primary = resolve(anchor, componentTree);
        if (primary.isPresent()) return primary;

        // Table-specific fallback: match by tableId + cellLogicalAddress
        if (anchor.getTableId() != null && anchor.getCellLogicalAddress() != null) {
            return componentTree.values().stream()
                .filter(c -> c.getAnchor() != null
                    && anchor.getTableId().equals(c.getAnchor().getTableId())
                    && anchor.getCellLogicalAddress().equals(c.getAnchor().getCellLogicalAddress()))
                .findFirst();
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------------
    //  Index helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a flat stableId → component map from the entire component tree.
     * Suitable for O(1) lookups in resolve/remap operations.
     */
    public Map<String, DocumentComponent> buildIndex(DocumentComponent root) {
        Map<String, DocumentComponent> index = new LinkedHashMap<>();
        indexRecursive(root, index);
        return index;
    }

    private void indexRecursive(DocumentComponent node, Map<String, DocumentComponent> index) {
        if (node == null) return;
        index.put(node.getId(), node);
        if (node.getChildren() != null) {
            for (DocumentComponent child : node.getChildren()) {
                indexRecursive(child, index);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Fingerprint
    // -----------------------------------------------------------------------

    private String computeFingerprint(ComponentType type,
                                      String textExcerpt,
                                      String styleId,
                                      String parentPath) {
        String raw = String.join("|",
            type != null ? type.name() : "",
            textExcerpt != null ? textExcerpt.substring(0, Math.min(100, textExcerpt.length())) : "",
            styleId != null ? styleId : "",
            parentPath != null ? parentPath : "");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(raw.getBytes(StandardCharsets.UTF_8));
            // Truncate to 16 hex chars for compactness
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in the JDK
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
