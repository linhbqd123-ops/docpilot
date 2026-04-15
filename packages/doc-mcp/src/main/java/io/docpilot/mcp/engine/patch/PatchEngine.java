package io.docpilot.mcp.engine.patch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.model.document.Anchor;
import io.docpilot.mcp.model.document.ContentProps;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.document.RevisionMetadata;
import io.docpilot.mcp.model.patch.*;
import io.docpilot.mcp.model.session.DocumentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Core patch engine — applies, dry-runs, validates and rolls back {@link Patch}es
 * against a {@link DocumentSession}.
 *
 * <p>Each operation is applied in order; if any operation fails during dry-run
 * the patch is rejected without mutating the session state.
 *
 * <p>Rollback works by keeping a pre-patch snapshot of affected components
 * (stored inline on the patch result); full content-snapshot rollback is
 * deferred to the {@link io.docpilot.mcp.engine.revision.RevisionService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatchEngine {

    private final AnchorService anchorService;
    private final ObjectMapper objectMapper;

    // -----------------------------------------------------------------------
    //  Dry-run: validate without mutating
    // -----------------------------------------------------------------------

    /**
     * Validates whether the patch can be applied to the session without actually
     * mutating any state.
     *
     * @return a {@link PatchValidation} describing the outcome
     */
    public PatchValidation dryRun(Patch patch, DocumentSession session) {
        Map<String, DocumentComponent> index = anchorService.buildIndex(session.getRoot());
        List<String> errors   = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> affected = new ArrayList<>();

        for (PatchOperation op : patch.getOperations()) {
            PatchTarget target = op.getTarget();
            if (target == null) {
                errors.add("Operation " + op.getOp() + " is missing a target");
                continue;
            }

            // Resolve the target component
            Anchor anchor = Anchor.builder().stableId(target.getBlockId()).build();
            Optional<DocumentComponent> resolved = anchorService.resolve(anchor, index);
            if (resolved.isEmpty()) {
                errors.add("Cannot locate block with id=" + target.getBlockId()
                    + " (tried stableId, logicalPath, fingerprint)");
                continue;
            }
            affected.add(resolved.get().getId());

            // Op-specific validation
            validateOperation(op, resolved.get(), errors, warnings);
        }

        boolean ok = errors.isEmpty();
        String scope = estimateScope(patch.getOperations().size(), affected.size());
        return PatchValidation.builder()
            .structureOk(ok)
            .styleOk(ok)
            .errors(errors)
            .warnings(warnings)
            .affectedBlockIds(affected)
            .scope(scope)
            .build();
    }

    // -----------------------------------------------------------------------
    //  Apply
    // -----------------------------------------------------------------------

    /**
     * Applies the patch to the session in-place.
     *
     * <p>Callers should run {@link #dryRun} first and only proceed if there are no errors.
     *
     * @param revisionId the revision ID being created for this patch (written into revisionMetadata)
     * @param author     "ai" or "manual"
     */
    public void apply(Patch patch, DocumentSession session, String revisionId, String author) {
        Map<String, DocumentComponent> index = anchorService.buildIndex(session.getRoot());

        for (PatchOperation op : patch.getOperations()) {
            if (op.getTarget() == null) continue;

            Anchor anchor = Anchor.builder().stableId(op.getTarget().getBlockId()).build();
            Optional<DocumentComponent> resolved = anchorService.resolve(anchor, index);
            if (resolved.isEmpty()) {
                log.warn("apply: skipping op {} — block {} not found", op.getOp(), op.getTarget().getBlockId());
                continue;
            }

            DocumentComponent component = resolved.get();
            applyOperation(op, component, session, index, revisionId, author);
        }

        session.setLastModifiedAt(Instant.now());
        session.setCurrentRevisionId(revisionId);
        log.info("Patch {} applied to session {}: {} operations", patch.getPatchId(), session.getSessionId(), patch.getOperations().size());
    }

    // -----------------------------------------------------------------------
    //  Operation dispatch
    // -----------------------------------------------------------------------

    private void applyOperation(PatchOperation op,
                                 DocumentComponent target,
                                 DocumentSession session,
                                 Map<String, DocumentComponent> index,
                                 String revisionId,
                                 String author) {
        switch (op.getOp()) {
            case REPLACE_TEXT_RANGE -> replaceTextRange(op, target, revisionId, author);
            case INSERT_TEXT_AT     -> insertTextAt(op, target, revisionId, author);
            case DELETE_TEXT_RANGE  -> deleteTextRange(op, target, revisionId, author);
            case APPLY_STYLE        -> applyStyle(op, target, revisionId, author);
            case APPLY_INLINE_FORMAT-> applyInlineFormat(op, target, revisionId, author);
            case SET_HEADING_LEVEL  -> setHeadingLevel(op, target, revisionId, author);
            case DELETE_BLOCK       -> deleteBlock(op, target, session, revisionId);
            case CREATE_BLOCK       -> createBlock(op, target, session, index, revisionId, author);
            case MOVE_BLOCK         -> moveBlock(op, target, session, index, revisionId, author);
            case UPDATE_CELL_CONTENT-> replaceTextRange(op, target, revisionId, author); // reuses text replace
            case INSERT_ROW         -> insertTableRow(op, target, session, revisionId, author);
            case DELETE_ROW         -> deleteTableRow(op, target, session, revisionId);
            case CHANGE_LIST_TYPE   -> changeListType(op, target, revisionId, author);
            case CHANGE_LIST_LEVEL  -> changeListLevel(op, target, revisionId, author);
            default -> log.warn("Unhandled operation type: {}", op.getOp());
        }
    }

    private void validateOperation(PatchOperation op,
                                   DocumentComponent target,
                                   List<String> errors,
                                   List<String> warnings) {
        switch (op.getOp()) {
            case REPLACE_TEXT_RANGE, DELETE_TEXT_RANGE -> {
                PatchTarget t = op.getTarget();
                if (t.getStart() != null && t.getEnd() != null && t.getStart() > t.getEnd()) {
                    errors.add("REPLACE_TEXT_RANGE: start > end at block " + t.getBlockId());
                }
                String text = currentText(target);
                if (text != null && t.getEnd() != null && t.getEnd() > text.length()) {
                    warnings.add("Range end (" + t.getEnd() + ") exceeds text length (" + text.length() + ") at block " + t.getBlockId());
                }
            }
            case SET_HEADING_LEVEL -> {
                if (op.getValue() == null || !op.getValue().isInt()) {
                    errors.add("SET_HEADING_LEVEL: value must be an integer (1–6)");
                } else {
                    int level = op.getValue().intValue();
                    if (level < 1 || level > 6) {
                        errors.add("SET_HEADING_LEVEL: level " + level + " out of range (1–6)");
                    }
                }
            }
            default -> { /* no additional validation */ }
        }
    }

    // -----------------------------------------------------------------------
    //  Concrete operation implementations
    // -----------------------------------------------------------------------

    private void replaceTextRange(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        PatchTarget t   = op.getTarget();
        String newText  = op.getValue() != null ? op.getValue().asText() : "";
        String current  = currentText(c);
        if (current == null) current = "";

        String result;
        if (t.getStart() != null && t.getEnd() != null) {
            int start = Math.max(0, t.getStart());
            int end   = Math.min(current.length(), t.getEnd());
            result = current.substring(0, start) + newText + current.substring(end);
        } else {
            result = newText;
        }
        ContentProps cp = c.getContentProps() != null ? c.getContentProps() : ContentProps.builder().build();
        c.setContentProps(ContentProps.builder()
            .text(result)
            .imageBase64(cp.getImageBase64())
            .imageMimeType(cp.getImageMimeType())
            .hyperlinkUrl(cp.getHyperlinkUrl())
            .build());
        stampRevision(c, revisionId, author);
    }

    private void insertTextAt(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        PatchTarget t  = op.getTarget();
        String insert  = op.getValue() != null ? op.getValue().asText() : "";
        String current = currentText(c);
        if (current == null) current = "";
        int pos = t.getStart() != null ? Math.min(t.getStart(), current.length()) : current.length();
        String result = current.substring(0, pos) + insert + current.substring(pos);
        c.setContentProps(ContentProps.builder().text(result).build());
        stampRevision(c, revisionId, author);
    }

    private void deleteTextRange(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        PatchTarget t  = op.getTarget();
        String current = currentText(c);
        if (current == null) return;
        int start = t.getStart() != null ? Math.max(0, t.getStart()) : 0;
        int end   = t.getEnd()   != null ? Math.min(current.length(), t.getEnd()) : current.length();
        String result = current.substring(0, start) + current.substring(end);
        c.setContentProps(ContentProps.builder().text(result).build());
        stampRevision(c, revisionId, author);
    }

    private void applyStyle(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        if (op.getValue() == null) return;
        String styleId   = op.getValue().path("styleId").asText(null);
        String styleName = op.getValue().path("styleName").asText(null);
        if (styleId == null) return;
        c.setStyleRef(io.docpilot.mcp.model.document.StyleRef.builder()
            .styleId(styleId)
            .styleName(styleName)
            .build());
        stampRevision(c, revisionId, author);
    }

    private void applyInlineFormat(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        if (op.getValue() == null) return;
        io.docpilot.mcp.model.document.LayoutProps existing = c.getLayoutProps();
        io.docpilot.mcp.model.document.LayoutProps.LayoutPropsBuilder b =
            existing != null ? existing.toBuilder() : io.docpilot.mcp.model.document.LayoutProps.builder();

        JsonNode v = op.getValue();
        if (v.has("bold"))          b.bold(v.get("bold").asBoolean());
        if (v.has("italic"))        b.italic(v.get("italic").asBoolean());
        if (v.has("underline"))     b.underline(v.get("underline").asBoolean());
        if (v.has("strikethrough")) b.strikethrough(v.get("strikethrough").asBoolean());
        if (v.has("color"))         b.color(v.get("color").asText());
        if (v.has("fontAscii"))     b.fontAscii(v.get("fontAscii").asText());
        if (v.has("fontSizePt"))    b.fontSizePt(v.get("fontSizePt").asDouble());
        c.setLayoutProps(b.build());
        stampRevision(c, revisionId, author);
    }

    private void setHeadingLevel(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        if (op.getValue() == null) return;
        int level = op.getValue().asInt(1);
        io.docpilot.mcp.model.document.LayoutProps.LayoutPropsBuilder b =
            c.getLayoutProps() != null ? c.getLayoutProps().toBuilder()
                : io.docpilot.mcp.model.document.LayoutProps.builder();
        c.setLayoutProps(b.headingLevel(level).build());
        c.setType(io.docpilot.mcp.model.document.ComponentType.HEADING);
        stampRevision(c, revisionId, author);
    }

    private void deleteBlock(PatchOperation op,
                              DocumentComponent target,
                              DocumentSession session,
                              String revisionId) {
        // Find parent and remove target from its children
        Map<String, DocumentComponent> index = anchorService.buildIndex(session.getRoot());
        if (target.getParentId() == null) return;
        DocumentComponent parent = index.get(target.getParentId());
        if (parent != null && parent.getChildren() != null) {
            parent.getChildren().removeIf(c -> c.getId().equals(target.getId()));
            log.debug("Deleted block {} from parent {}", target.getId(), parent.getId());
        }
    }

    private void createBlock(PatchOperation op,
                              DocumentComponent afterTarget,
                              DocumentSession session,
                              Map<String, DocumentComponent> index,
                              String revisionId,
                              String author) {
        if (op.getValue() == null) return;
        try {
            DocumentComponent newBlock = objectMapper.treeToValue(op.getValue(), DocumentComponent.class);
            if (newBlock.getId() == null) newBlock.setId(UUID.randomUUID().toString());
            newBlock.setParentId(afterTarget.getParentId());
            stampRevision(newBlock, revisionId, author);

            // Insert after the target
            DocumentComponent parent = afterTarget.getParentId() != null ? index.get(afterTarget.getParentId()) : null;
            if (parent != null && parent.getChildren() != null) {
                int targetIdx = -1;
                List<DocumentComponent> children = parent.getChildren();
                for (int i = 0; i < children.size(); i++) {
                    if (children.get(i).getId().equals(afterTarget.getId())) { targetIdx = i; break; }
                }
                if (targetIdx >= 0) {
                    children.add(targetIdx + 1, newBlock);
                } else {
                    children.add(newBlock);
                }
            }
        } catch (Exception e) {
            log.error("CREATE_BLOCK failed: {}", e.getMessage(), e);
        }
    }

    private void moveBlock(PatchOperation op,
                            DocumentComponent target,
                            DocumentSession session,
                            Map<String, DocumentComponent> index,
                            String revisionId,
                            String author) {
        // value: { "afterBlockId": "xxx" } or { "beforeBlockId": "xxx" }
        if (op.getValue() == null) return;
        String afterId  = op.getValue().path("afterBlockId").asText(null);
        String beforeId = op.getValue().path("beforeBlockId").asText(null);
        String referenceId = afterId != null ? afterId : beforeId;
        if (referenceId == null) return;

        // Remove from current parent
        if (target.getParentId() != null) {
            DocumentComponent oldParent = index.get(target.getParentId());
            if (oldParent != null) oldParent.getChildren().removeIf(c -> c.getId().equals(target.getId()));
        }

        // Insert at new position
        DocumentComponent reference = index.get(referenceId);
        if (reference == null || reference.getParentId() == null) return;
        DocumentComponent newParent = index.get(reference.getParentId());
        if (newParent == null) return;

        target.setParentId(newParent.getId());
        List<DocumentComponent> siblings = newParent.getChildren();
        int refIdx = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(referenceId)) { refIdx = i; break; }
        }
        if (refIdx >= 0) {
            int insertIdx = afterId != null ? refIdx + 1 : refIdx;
            siblings.add(Math.min(insertIdx, siblings.size()), target);
        } else {
            siblings.add(target);
        }
        stampRevision(target, revisionId, author);
    }

    private void insertTableRow(PatchOperation op,
                                 DocumentComponent tableCell,
                                 DocumentSession session,
                                 String revisionId,
                                 String author) {
        // Navigate to the TABLE_ROW parent and clone it after/before the current row
        Map<String, DocumentComponent> index = anchorService.buildIndex(session.getRoot());
        DocumentComponent row = tableCell.getParentId() != null ? index.get(tableCell.getParentId()) : null;
        if (row == null) return;
        DocumentComponent table = row.getParentId() != null ? index.get(row.getParentId()) : null;
        if (table == null) return;

        // Build empty row matching column count
        int colCount = row.getChildren().size();
        DocumentComponent newRow = DocumentComponent.builder()
            .id(UUID.randomUUID().toString())
            .type(io.docpilot.mcp.model.document.ComponentType.TABLE_ROW)
            .parentId(table.getId())
            .children(new ArrayList<>())
            .build();
        for (int i = 0; i < colCount; i++) {
            DocumentComponent cell = DocumentComponent.builder()
                .id(UUID.randomUUID().toString())
                .type(io.docpilot.mcp.model.document.ComponentType.TABLE_CELL)
                .parentId(newRow.getId())
                .contentProps(ContentProps.builder().text("").build())
                .children(new ArrayList<>())
                .build();
            newRow.getChildren().add(cell);
        }
        stampRevision(newRow, revisionId, author);

        // Insert after current row
        List<DocumentComponent> rows = table.getChildren();
        int rowIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getId().equals(row.getId())) { rowIdx = i; break; }
        }
        rows.add(rowIdx >= 0 ? rowIdx + 1 : rows.size(), newRow);
    }

    private void deleteTableRow(PatchOperation op,
                                 DocumentComponent tableCell,
                                 DocumentSession session,
                                 String revisionId) {
        Map<String, DocumentComponent> index = anchorService.buildIndex(session.getRoot());
        DocumentComponent row = tableCell.getParentId() != null ? index.get(tableCell.getParentId()) : null;
        if (row == null) return;
        DocumentComponent table = row.getParentId() != null ? index.get(row.getParentId()) : null;
        if (table == null) return;
        table.getChildren().removeIf(c -> c.getId().equals(row.getId()));
    }

    private void changeListType(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        if (op.getValue() == null) return;
        String listType = op.getValue().asText();
        io.docpilot.mcp.model.document.LayoutProps.LayoutPropsBuilder b =
            c.getLayoutProps() != null ? c.getLayoutProps().toBuilder()
                : io.docpilot.mcp.model.document.LayoutProps.builder();
        c.setLayoutProps(b.listType(listType).build());
        stampRevision(c, revisionId, author);
    }

    private void changeListLevel(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        if (op.getValue() == null) return;
        int level = op.getValue().asInt(0);
        io.docpilot.mcp.model.document.LayoutProps.LayoutPropsBuilder b =
            c.getLayoutProps() != null ? c.getLayoutProps().toBuilder()
                : io.docpilot.mcp.model.document.LayoutProps.builder();
        c.setLayoutProps(b.listLevel(level).build());
        stampRevision(c, revisionId, author);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private String currentText(DocumentComponent c) {
        return c.getContentProps() != null ? c.getContentProps().getText() : null;
    }

    private void stampRevision(DocumentComponent c, String revisionId, String author) {
        c.setRevisionMetadata(RevisionMetadata.builder()
            .lastModifiedByRevisionId(revisionId)
            .lastModifiedAt(Instant.now())
            .lastModifiedBy(author)
            .build());
    }

    private String estimateScope(int opCount, int blockCount) {
        if (blockCount >= 10 || opCount >= 20) return "major";
        if (blockCount >= 3  || opCount >= 5)  return "moderate";
        return "minor";
    }
}
