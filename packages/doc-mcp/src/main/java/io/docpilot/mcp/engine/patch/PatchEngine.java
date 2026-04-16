package io.docpilot.mcp.engine.patch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.model.document.Anchor;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.ContentProps;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.document.LayoutProps;
import io.docpilot.mcp.model.document.RevisionMetadata;
import io.docpilot.mcp.model.document.StyleRef;
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

            Optional<DocumentComponent> resolved = resolveTarget(target, session, index);
            if (resolved.isEmpty()) {
                errors.add("Cannot locate target " + describeTarget(target));
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

            Optional<DocumentComponent> resolved = resolveTarget(op.getTarget(), session, index);
            if (resolved.isEmpty()) {
                log.warn("apply: skipping op {} — target {} not found", op.getOp(), describeTarget(op.getTarget()));
                continue;
            }

            DocumentComponent component = resolved.get();
            applyOperation(op, component, session, index, revisionId, author);
            index = anchorService.buildIndex(session.getRoot());
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
            case REPLACE_TEXT_RANGE -> replaceTextRange(op, target, index, revisionId, author);
            case INSERT_TEXT_AT     -> insertTextAt(op, target, index, revisionId, author);
            case DELETE_TEXT_RANGE  -> deleteTextRange(op, target, index, revisionId, author);
            case APPLY_STYLE        -> applyStyle(op, target, revisionId, author);
            case APPLY_INLINE_FORMAT-> applyInlineFormat(op, target, revisionId, author);
            case SET_HEADING_LEVEL  -> setHeadingLevel(op, target, revisionId, author);
            case DELETE_BLOCK       -> deleteBlock(op, target, session, revisionId);
            case CREATE_BLOCK       -> createBlock(op, target, session, index, revisionId, author);
            case CLONE_BLOCK        -> cloneBlock(op, target, session, index, revisionId, author);
            case MOVE_BLOCK         -> moveBlock(op, target, session, index, revisionId, author);
            case UPDATE_CELL_CONTENT-> replaceTextRange(op, target, index, revisionId, author); // reuses text replace
            case INSERT_ROW         -> insertTableRow(op, target, session, revisionId, author);
            case DELETE_ROW         -> deleteTableRow(op, target, session, revisionId);
            case CHANGE_LIST_TYPE   -> changeListType(op, target, revisionId, author);
            case CHANGE_LIST_LEVEL  -> changeListLevel(op, target, revisionId, author);
            case NORMALIZE_TEXT_RUNS-> normalizeTextRuns(target, revisionId, author);
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
                Integer level = extractIntegerValue(op.getValue(), "level", "headingLevel", "heading_level");
                if (level == null) {
                    errors.add("SET_HEADING_LEVEL: value must be an integer (1–6)");
                } else {
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

    private void replaceTextRange(PatchOperation op,
                                  DocumentComponent c,
                                  Map<String, DocumentComponent> index,
                                  String revisionId,
                                  String author) {
        PatchTarget t   = op.getTarget();
        String newText  = extractTextValue(op.getValue());
        String current  = currentText(c);
        if (current == null) current = "";

        int start = t.getStart() != null ? Math.max(0, Math.min(t.getStart(), current.length())) : 0;
        int end = t.getEnd() != null ? Math.max(start, Math.min(t.getEnd(), current.length())) : current.length();
        mutateText(c, index, start, end, newText, revisionId, author);
    }

    private void insertTextAt(PatchOperation op,
                              DocumentComponent c,
                              Map<String, DocumentComponent> index,
                              String revisionId,
                              String author) {
        PatchTarget t  = op.getTarget();
        String insert  = extractTextValue(op.getValue());
        String current = currentText(c);
        if (current == null) current = "";
        int pos = t.getStart() != null ? Math.min(Math.max(t.getStart(), 0), current.length()) : current.length();
        mutateText(c, index, pos, pos, insert, revisionId, author);
    }

    private void deleteTextRange(PatchOperation op,
                                 DocumentComponent c,
                                 Map<String, DocumentComponent> index,
                                 String revisionId,
                                 String author) {
        PatchTarget t  = op.getTarget();
        String current = currentText(c);
        if (current == null) return;
        int start = t.getStart() != null ? Math.max(0, t.getStart()) : 0;
        int end   = t.getEnd()   != null ? Math.min(current.length(), t.getEnd()) : current.length();
        mutateText(c, index, start, Math.max(start, end), "", revisionId, author);
    }

    private void applyStyle(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        if (op.getValue() == null) return;
        String styleId   = extractStringValue(op.getValue(), "styleId", "style_id");
        String styleName = extractStringValue(op.getValue(), "styleName", "style_name");
        if (styleId == null) return;
        c.setStyleRef(StyleRef.builder()
            .styleId(styleId)
            .styleName(styleName)
            .build());
        stampRevision(c, revisionId, author);
    }

    private void applyInlineFormat(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        if (op.getValue() == null) return;
        LayoutProps existing = c.getLayoutProps();
        LayoutProps.LayoutPropsBuilder b =
            existing != null ? existing.toBuilder() : LayoutProps.builder();

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
        Integer level = extractIntegerValue(op.getValue(), "level", "headingLevel", "heading_level");
        if (level == null) {
            level = 1;
        }
        LayoutProps.LayoutPropsBuilder b =
            c.getLayoutProps() != null ? c.getLayoutProps().toBuilder()
                : LayoutProps.builder();
        c.setLayoutProps(b.headingLevel(level).build());
        c.setType(ComponentType.HEADING);
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
            DocumentComponent parent = afterTarget.getParentId() != null ? index.get(afterTarget.getParentId()) : null;
            if (parent == null) {
                return;
            }

            if (parent.getChildren() == null) {
                parent.setChildren(new ArrayList<>());
            }

            String parentPath = parent.getAnchor() != null ? parent.getAnchor().getLogicalPath() : "document";
            DocumentComponent newBlock = buildBlockFromValue(op.getValue(), parent.getId(), afterTarget, parentPath, revisionId, author);
            String logicalPath = parentPath + "/" + newBlock.getType().name().toLowerCase() + "[" + (parent.getChildren().size() + 1) + "]";
            if (newBlock.getAnchor() == null) {
                String styleId = newBlock.getStyleRef() != null ? newBlock.getStyleRef().getStyleId() : null;
                newBlock.setAnchor(anchorService.generate(newBlock.getType(), logicalPath, currentText(newBlock), styleId, parentPath));
            }
            stampRevisionTree(newBlock, revisionId, author);

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
        } catch (Exception e) {
            log.error("CREATE_BLOCK failed: {}", e.getMessage(), e);
        }
    }

    private void cloneBlock(PatchOperation op,
                             DocumentComponent target,
                             DocumentSession session,
                             Map<String, DocumentComponent> index,
                             String revisionId,
                             String author) {
        if (target.getParentId() == null) {
            return;
        }

        DocumentComponent parent = index.get(target.getParentId());
        if (parent == null) {
            return;
        }
        if (parent.getChildren() == null) {
            parent.setChildren(new ArrayList<>());
        }

        int targetIndex = -1;
        for (int i = 0; i < parent.getChildren().size(); i++) {
            if (parent.getChildren().get(i).getId().equals(target.getId())) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) {
            return;
        }

        String parentPath = parent.getAnchor() != null ? parent.getAnchor().getLogicalPath() : "document";
        String logicalPath = parentPath + "/" + target.getType().name().toLowerCase(Locale.ROOT) + "[" + (targetIndex + 2) + "]";
        DocumentComponent cloned = cloneComponentTree(target, parent.getId(), parentPath, logicalPath, revisionId, author);
        parent.getChildren().add(targetIndex + 1, cloned);
        stampRevision(parent, revisionId, author);
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
        Map<String, DocumentComponent> index = anchorService.buildIndex(session.getRoot());
        DocumentComponent row = resolveRowTarget(tableCell, index);
        DocumentComponent table = row != null && row.getParentId() != null ? index.get(row.getParentId()) : null;
        if (table == null && tableCell.getType() == ComponentType.TABLE) {
            table = tableCell;
        }
        if (table == null) return;

        int rowInsertIndex = table.getChildren() != null ? table.getChildren().size() : 0;
        if (row != null && table.getChildren() != null) {
            for (int i = 0; i < table.getChildren().size(); i++) {
                if (table.getChildren().get(i).getId().equals(row.getId())) {
                    rowInsertIndex = i + 1;
                    break;
                }
            }
        }

        int colCount = row != null && row.getChildren() != null
            ? row.getChildren().size()
            : (!table.getChildren().isEmpty() ? table.getChildren().get(0).getChildren().size() : 1);
        String tablePath = table.getAnchor() != null ? table.getAnchor().getLogicalPath() : "table";
        String rowPath = tablePath + "/row[" + (rowInsertIndex + 1) + "]";
        String tableFingerprint = table.getAnchor() != null ? table.getAnchor().getStructuralFingerprint() : table.getId();
        DocumentComponent newRow = row != null
            ? cloneTableRow(row, table.getId(), table.getId(), rowInsertIndex, rowPath, tablePath, tableFingerprint, revisionId, author)
            : buildBlankTableRow(table.getId(), rowInsertIndex, colCount, tablePath, rowPath, tableFingerprint, revisionId, author);
        stampRevisionTree(newRow, revisionId, author);

        List<DocumentComponent> rows = table.getChildren();
        rows.add(Math.min(rowInsertIndex, rows.size()), newRow);
    }

    private void deleteTableRow(PatchOperation op,
                                 DocumentComponent tableCell,
                                 DocumentSession session,
                                 String revisionId) {
        Map<String, DocumentComponent> index = anchorService.buildIndex(session.getRoot());
        DocumentComponent row = resolveRowTarget(tableCell, index);
        if (row == null) return;
        DocumentComponent table = row.getParentId() != null ? index.get(row.getParentId()) : null;
        if (table == null) return;
        table.getChildren().removeIf(c -> c.getId().equals(row.getId()));
    }

    private void changeListType(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        String listType = extractStringValue(op.getValue(), "listType", "list_type", "type");
        if (listType == null) return;
        LayoutProps.LayoutPropsBuilder b =
            c.getLayoutProps() != null ? c.getLayoutProps().toBuilder()
                : LayoutProps.builder();
        c.setLayoutProps(b.listType(listType).build());
        stampRevision(c, revisionId, author);
    }

    private void changeListLevel(PatchOperation op, DocumentComponent c, String revisionId, String author) {
        Integer level = extractIntegerValue(op.getValue(), "level", "listLevel", "list_level");
        if (level == null) {
            level = 0;
        }
        LayoutProps.LayoutPropsBuilder b =
            c.getLayoutProps() != null ? c.getLayoutProps().toBuilder()
                : LayoutProps.builder();
        c.setLayoutProps(b.listLevel(level).build());
        stampRevision(c, revisionId, author);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private Optional<DocumentComponent> resolveTarget(PatchTarget target,
                                                      DocumentSession session,
                                                      Map<String, DocumentComponent> index) {
        if (target == null) {
            return Optional.empty();
        }

        if (target.getRunId() != null) {
            DocumentComponent run = index.get(target.getRunId());
            if (run != null) {
                return Optional.of(run);
            }
        }

        if (target.getRowId() != null
            && target.getBlockId() == null
            && target.getCellId() == null
            && target.getCellLogicalAddress() == null) {
            return Optional.ofNullable(index.get(target.getRowId()));
        }

        if (target.getCellId() != null || target.getCellLogicalAddress() != null) {
            Anchor anchor = Anchor.builder()
                .stableId(target.getCellId() != null ? target.getCellId() : target.getBlockId())
                .tableId(target.getTableId())
                .rowId(target.getRowId())
                .cellLogicalAddress(target.getCellLogicalAddress())
                .build();
            Optional<DocumentComponent> resolvedCell = anchorService.resolveCellAnchor(anchor, index);
            if (resolvedCell.isPresent()) {
                return resolvedCell;
            }
        }

        if (target.getTableId() != null && target.getBlockId() == null) {
            DocumentComponent table = index.get(target.getTableId());
            if (table != null) {
                return Optional.of(table);
            }
        }

        if (target.getBlockId() == null) {
            return Optional.empty();
        }

        Anchor anchor = Anchor.builder().stableId(target.getBlockId()).build();
        return anchorService.resolve(anchor, index);
    }

    private String describeTarget(PatchTarget target) {
        if (target == null) {
            return "<missing>";
        }
        if (target.getCellLogicalAddress() != null) {
            return "table=" + target.getTableId() + ", cell=" + target.getCellLogicalAddress();
        }
        if (target.getCellId() != null) {
            return "cell=" + target.getCellId();
        }
        if (target.getRowId() != null) {
            return "row=" + target.getRowId();
        }
        if (target.getTableId() != null) {
            return "table=" + target.getTableId();
        }
        return "block=" + target.getBlockId();
    }

    private String currentText(DocumentComponent c) {
        if (hasTextRunChildren(c)) {
            StringBuilder text = new StringBuilder();
            for (DocumentComponent child : textRunChildren(c)) {
                if (child.getContentProps() != null && child.getContentProps().getText() != null) {
                    text.append(child.getContentProps().getText());
                }
            }
            return text.toString();
        }
        return c.getContentProps() != null ? c.getContentProps().getText() : null;
    }

    private ContentProps withUpdatedText(ContentProps existing, String text) {
        ContentProps current = existing != null ? existing : ContentProps.builder().build();
        return ContentProps.builder()
            .text(text)
            .imageBase64(current.getImageBase64())
            .imageMimeType(current.getImageMimeType())
            .imageWidthEmu(current.getImageWidthEmu())
            .imageHeightEmu(current.getImageHeightEmu())
            .imageAltText(current.getImageAltText())
            .hyperlinkUrl(current.getHyperlinkUrl())
            .hyperlinkText(current.getHyperlinkText())
            .fieldInstruction(current.getFieldInstruction())
            .fieldResult(current.getFieldResult())
            .cellMergedVertically(current.getCellMergedVertically())
            .cellMergedHorizontally(current.getCellMergedHorizontally())
            .build();
    }

    private String extractTextValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        String nested = extractStringValue(value, "text", "newText", "new_text");
        return nested != null ? nested : "";
    }

    private Integer extractIntegerValue(JsonNode value, String... fieldNames) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        for (String fieldName : fieldNames) {
            JsonNode field = value.path(fieldName);
            if (field.isMissingNode() || field.isNull()) {
                continue;
            }
            if (field.isInt() || field.isLong()) {
                return field.asInt();
            }
            if (field.isTextual()) {
                try {
                    return Integer.parseInt(field.asText());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String extractStringValue(JsonNode value, String... fieldNames) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        for (String fieldName : fieldNames) {
            JsonNode field = value.path(fieldName);
            if (!field.isMissingNode() && !field.isNull()) {
                return field.asText();
            }
        }
        return null;
    }

    private DocumentComponent buildBlockFromValue(JsonNode value,
                                                 String parentId,
                                                 DocumentComponent afterTarget,
                                                 String parentPath,
                                                 String revisionId,
                                                 String author) {
        if (value.has("contentProps") || value.has("anchor") || value.has("styleRef")) {
            try {
                DocumentComponent component = objectMapper.treeToValue(value, DocumentComponent.class);
                if (component.getId() == null) {
                    component.setId(UUID.randomUUID().toString());
                }
                component.setParentId(parentId);
                if (component.getChildren() == null) {
                    component.setChildren(new ArrayList<>());
                }
                return component;
            } catch (Exception ignored) {
                log.debug("Falling back to simplified CREATE_BLOCK payload parsing");
            }
        }

        String requestedText = extractTextValue(value);
        if (afterTarget != null) {
            String logicalPath = parentPath + "/" + afterTarget.getType().name().toLowerCase(Locale.ROOT) + "[clone]";
            DocumentComponent cloned = cloneComponentTree(afterTarget, parentId, parentPath, logicalPath, revisionId, author);
            if (value.hasNonNull("type")) {
                cloned.setType(componentTypeFrom(extractStringValue(value, "type")));
            }
            if (value.hasNonNull("styleId") || value.hasNonNull("style_id") || value.hasNonNull("styleName") || value.hasNonNull("style_name")) {
                cloned.setStyleRef(StyleRef.builder()
                    .styleId(extractStringValue(value, "styleId", "style_id"))
                    .styleName(extractStringValue(value, "styleName", "style_name"))
                    .build());
            }
            if (value.hasNonNull("headingLevel") || value.hasNonNull("heading_level") || value.hasNonNull("level")) {
                Integer headingLevel = extractIntegerValue(value, "headingLevel", "heading_level", "level");
                LayoutProps layout = cloned.getLayoutProps() != null ? cloned.getLayoutProps() : LayoutProps.builder().build();
                cloned.setLayoutProps(layout.toBuilder().headingLevel(headingLevel).build());
            }
            overwriteText(cloned, requestedText, revisionId, author);
            return cloned;
        }

        ComponentType type = componentTypeFrom(extractStringValue(value, "type"));
        String styleId = extractStringValue(value, "styleId", "style_id");
        String styleName = extractStringValue(value, "styleName", "style_name");

        LayoutProps.LayoutPropsBuilder layout = LayoutProps.builder();
        Integer headingLevel = extractIntegerValue(value, "headingLevel", "heading_level", "level");
        if (type == ComponentType.HEADING) {
            layout.headingLevel(headingLevel != null ? headingLevel : 1);
        }
        Integer listLevel = extractIntegerValue(value, "listLevel", "list_level");
        if (listLevel != null) {
            layout.listLevel(listLevel);
        }
        String listType = extractStringValue(value, "listType", "list_type");
        if (listType != null) {
            layout.listType(listType);
        }

        return DocumentComponent.builder()
            .id(UUID.randomUUID().toString())
            .type(type)
            .parentId(parentId)
            .styleRef(styleId != null || styleName != null
                ? StyleRef.builder().styleId(styleId).styleName(styleName).build()
                : null)
            .layoutProps(layout.build())
            .contentProps(ContentProps.builder().text(requestedText).build())
            .children(new ArrayList<>())
            .build();
    }

    private ComponentType componentTypeFrom(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return ComponentType.PARAGRAPH;
        }
        try {
            return ComponentType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ComponentType.PARAGRAPH;
        }
    }

    private DocumentComponent resolveRowTarget(DocumentComponent target,
                                               Map<String, DocumentComponent> index) {
        if (target == null) {
            return null;
        }
        if (target.getType() == ComponentType.TABLE_ROW) {
            return target;
        }
        if (target.getType() == ComponentType.TABLE_CELL && target.getParentId() != null) {
            return index.get(target.getParentId());
        }
        return null;
    }

    private void stampRevision(DocumentComponent c, String revisionId, String author) {
        c.setRevisionMetadata(RevisionMetadata.builder()
            .lastModifiedByRevisionId(revisionId)
            .lastModifiedAt(Instant.now())
            .lastModifiedBy(author)
            .build());
    }

    private void stampRevisionTree(DocumentComponent c, String revisionId, String author) {
        stampRevision(c, revisionId, author);
        if (c.getChildren() != null) {
            for (DocumentComponent child : c.getChildren()) {
                stampRevisionTree(child, revisionId, author);
            }
        }
    }

    private void mutateText(DocumentComponent component,
                            Map<String, DocumentComponent> index,
                            int start,
                            int end,
                            String replacement,
                            String revisionId,
                            String author) {
        String current = currentText(component);
        if (current == null) {
            current = "";
        }
        int boundedStart = Math.max(0, Math.min(start, current.length()));
        int boundedEnd = Math.max(boundedStart, Math.min(end, current.length()));

        if (component.getType() == ComponentType.TEXT_RUN) {
            String updated = current.substring(0, boundedStart) + replacement + current.substring(boundedEnd);
            component.setContentProps(withUpdatedText(component.getContentProps(), updated));
            stampRevision(component, revisionId, author);
            syncParentText(component, index, revisionId, author);
            return;
        }

        if (hasTextRunChildren(component)) {
            rewriteTextRuns(component, boundedStart, boundedEnd, replacement, revisionId, author);
            syncBlockTextFromRuns(component);
            stampRevision(component, revisionId, author);
            return;
        }

        String updated = current.substring(0, boundedStart) + replacement + current.substring(boundedEnd);
        component.setContentProps(withUpdatedText(component.getContentProps(), updated));
        stampRevision(component, revisionId, author);
    }

    private void overwriteText(DocumentComponent component, String text, String revisionId, String author) {
        String nextText = text != null ? text : "";
        if (hasTextRunChildren(component)) {
            List<DocumentComponent> rebuiltRuns = new ArrayList<>();
            DocumentComponent template = textRunChildren(component).isEmpty() ? null : textRunChildren(component).get(0);
            if (template != null) {
                rebuiltRuns.add(cloneRunSegment(template, nextText, component.getId()));
            } else {
                rebuiltRuns.add(buildTextRun(nextText, component.getId(), component.getAnchor() != null ? component.getAnchor().getLogicalPath() : "block"));
            }
            reanchorRunChildren(component, rebuiltRuns);
        }
        component.setContentProps(withUpdatedText(component.getContentProps(), nextText));
        stampRevision(component, revisionId, author);
    }

    private void normalizeTextRuns(DocumentComponent component, String revisionId, String author) {
        if (!hasTextRunChildren(component)) {
            return;
        }
        overwriteText(component, currentText(component), revisionId, author);
    }

    private void rewriteTextRuns(DocumentComponent component,
                                 int start,
                                 int end,
                                 String replacement,
                                 String revisionId,
                                 String author) {
        List<DocumentComponent> runs = textRunChildren(component);
        if (runs.isEmpty()) {
            component.setContentProps(withUpdatedText(component.getContentProps(), replacement));
            return;
        }

        List<DocumentComponent> rebuiltRuns = new ArrayList<>();
        boolean replacementInserted = replacement == null || replacement.isEmpty();
        DocumentComponent insertionTemplate = runs.get(0);
        int cursor = 0;

        for (DocumentComponent run : runs) {
            String text = run.getContentProps() != null && run.getContentProps().getText() != null
                ? run.getContentProps().getText()
                : "";
            int runStart = cursor;
            int runEnd = cursor + text.length();
            cursor = runEnd;

            if (end <= runStart || start >= runEnd) {
                rebuiltRuns.add(cloneRunSegment(run, text, component.getId()));
                continue;
            }

            insertionTemplate = run;
            int localStart = Math.max(0, start - runStart);
            int localEnd = Math.min(text.length(), end - runStart);
            String prefix = text.substring(0, localStart);
            String suffix = text.substring(Math.max(localEnd, localStart));

            if (!prefix.isEmpty()) {
                rebuiltRuns.add(cloneRunSegment(run, prefix, component.getId()));
            }

            if (!replacementInserted && replacement != null && !replacement.isEmpty()) {
                rebuiltRuns.add(cloneRunSegment(insertionTemplate, replacement, component.getId()));
                replacementInserted = true;
            }

            if (!suffix.isEmpty()) {
                rebuiltRuns.add(cloneRunSegment(run, suffix, component.getId()));
            }
        }

        if (!replacementInserted && replacement != null && !replacement.isEmpty()) {
            rebuiltRuns.add(cloneRunSegment(insertionTemplate, replacement, component.getId()));
        }
        if (rebuiltRuns.isEmpty()) {
            rebuiltRuns.add(cloneRunSegment(insertionTemplate, "", component.getId()));
        }

        reanchorRunChildren(component, rebuiltRuns);
        for (DocumentComponent run : component.getChildren()) {
            stampRevision(run, revisionId, author);
        }
    }

    private void syncParentText(DocumentComponent child,
                                Map<String, DocumentComponent> index,
                                String revisionId,
                                String author) {
        if (child.getParentId() == null) {
            return;
        }
        DocumentComponent parent = index.get(child.getParentId());
        if (parent == null || !hasTextRunChildren(parent)) {
            return;
        }
        syncBlockTextFromRuns(parent);
        stampRevision(parent, revisionId, author);
    }

    private void syncBlockTextFromRuns(DocumentComponent component) {
        component.setContentProps(withUpdatedText(component.getContentProps(), currentText(component)));
    }

    private boolean hasTextRunChildren(DocumentComponent component) {
        return component.getChildren() != null && component.getChildren().stream().anyMatch(child -> child.getType() == ComponentType.TEXT_RUN);
    }

    private List<DocumentComponent> textRunChildren(DocumentComponent component) {
        if (component.getChildren() == null) {
            return List.of();
        }
        return component.getChildren().stream()
            .filter(child -> child.getType() == ComponentType.TEXT_RUN)
            .toList();
    }

    private DocumentComponent cloneRunSegment(DocumentComponent template, String text, String parentId) {
        return DocumentComponent.builder()
            .id(UUID.randomUUID().toString())
            .type(ComponentType.TEXT_RUN)
            .parentId(parentId)
            .styleRef(template.getStyleRef())
            .layoutProps(template.getLayoutProps())
            .contentProps(withUpdatedText(template.getContentProps(), text))
            .children(new ArrayList<>())
            .build();
    }

    private DocumentComponent buildTextRun(String text, String parentId, String parentPath) {
        return DocumentComponent.builder()
            .id(UUID.randomUUID().toString())
            .type(ComponentType.TEXT_RUN)
            .parentId(parentId)
            .contentProps(ContentProps.builder().text(text).build())
            .anchor(anchorService.generate(ComponentType.TEXT_RUN, parentPath + "/text_run[1]", text, null, parentPath))
            .children(new ArrayList<>())
            .build();
    }

    private void reanchorRunChildren(DocumentComponent parent, List<DocumentComponent> runs) {
        String parentPath = parent.getAnchor() != null ? parent.getAnchor().getLogicalPath() : "block";
        List<DocumentComponent> reanchored = new ArrayList<>();
        int index = 0;
        for (DocumentComponent run : runs) {
            index += 1;
            String text = run.getContentProps() != null && run.getContentProps().getText() != null ? run.getContentProps().getText() : "";
            run.setId(UUID.randomUUID().toString());
            run.setParentId(parent.getId());
            run.setAnchor(anchorService.generate(ComponentType.TEXT_RUN, parentPath + "/text_run[" + index + "]", text, null, parentPath));
            reanchored.add(run);
        }
        parent.setChildren(reanchored);
    }

    private DocumentComponent cloneComponentTree(DocumentComponent source,
                                                String parentId,
                                                String parentPath,
                                                String logicalPath,
                                                String revisionId,
                                                String author) {
        String styleId = source.getStyleRef() != null ? source.getStyleRef().getStyleId() : null;
        DocumentComponent cloned = DocumentComponent.builder()
            .id(UUID.randomUUID().toString())
            .type(source.getType())
            .parentId(parentId)
            .styleRef(source.getStyleRef())
            .layoutProps(source.getLayoutProps())
            .contentProps(source.getContentProps())
            .anchor(anchorService.generate(source.getType(), logicalPath, currentText(source), styleId, parentPath))
            .children(new ArrayList<>())
            .build();

        Map<String, Integer> childCounters = new HashMap<>();
        if (source.getChildren() != null) {
            for (DocumentComponent child : source.getChildren()) {
                String typeKey = child.getType() != null ? child.getType().name().toLowerCase(Locale.ROOT) : "component";
                int childIndex = childCounters.merge(typeKey, 1, Integer::sum);
                String childPath = logicalPath + "/" + typeKey + "[" + childIndex + "]";
                cloned.getChildren().add(cloneComponentTree(child, cloned.getId(), logicalPath, childPath, revisionId, author));
            }
        }
        stampRevision(cloned, revisionId, author);
        return cloned;
    }

    private DocumentComponent cloneTableRow(DocumentComponent row,
                                           String tableId,
                                           String parentId,
                                           int rowIndex,
                                           String rowPath,
                                           String tablePath,
                                           String tableFingerprint,
                                           String revisionId,
                                           String author) {
        DocumentComponent clonedRow = DocumentComponent.builder()
            .id(UUID.randomUUID().toString())
            .type(ComponentType.TABLE_ROW)
            .parentId(parentId)
            .styleRef(row.getStyleRef())
            .layoutProps(row.getLayoutProps())
            .contentProps(row.getContentProps())
            .anchor(anchorService.generate(ComponentType.TABLE_ROW, rowPath, "", null, tablePath))
            .children(new ArrayList<>())
            .build();

        int colIndex = 0;
        if (row.getChildren() != null) {
            for (DocumentComponent sourceCell : row.getChildren()) {
                String cellPath = rowPath + "/cell[" + (colIndex + 1) + "]";
                Anchor cellAnchor = anchorService.generateCellAnchor(
                    cellPath,
                    "",
                    sourceCell.getStyleRef() != null ? sourceCell.getStyleRef().getStyleId() : null,
                    rowPath,
                    tableId,
                    clonedRow.getId(),
                    rowIndex,
                    colIndex,
                    tableFingerprint
                );
                DocumentComponent clonedCell = cloneComponentTree(sourceCell, clonedRow.getId(), rowPath, cellPath, revisionId, author);
                clonedCell.setId(cellAnchor.getStableId());
                clonedCell.setAnchor(cellAnchor);
                clonedCell.setParentId(clonedRow.getId());
                overwriteText(clonedCell, "", revisionId, author);
                clonedRow.getChildren().add(clonedCell);
                colIndex += 1;
            }
        }
        return clonedRow;
    }

    private DocumentComponent buildBlankTableRow(String tableId,
                                                 int rowIndex,
                                                 int colCount,
                                                 String tablePath,
                                                 String rowPath,
                                                 String tableFingerprint,
                                                 String revisionId,
                                                 String author) {
        DocumentComponent newRow = DocumentComponent.builder()
            .id(UUID.randomUUID().toString())
            .type(ComponentType.TABLE_ROW)
            .parentId(tableId)
            .anchor(anchorService.generate(ComponentType.TABLE_ROW, rowPath, "", null, tablePath))
            .children(new ArrayList<>())
            .build();

        for (int i = 0; i < colCount; i++) {
            String cellPath = rowPath + "/cell[" + (i + 1) + "]";
            Anchor cellAnchor = anchorService.generateCellAnchor(
                cellPath,
                "",
                null,
                rowPath,
                tableId,
                newRow.getId(),
                rowIndex,
                i,
                tableFingerprint
            );
            DocumentComponent cell = DocumentComponent.builder()
                .id(cellAnchor.getStableId())
                .type(ComponentType.TABLE_CELL)
                .parentId(newRow.getId())
                .anchor(cellAnchor)
                .contentProps(ContentProps.builder().text("").build())
                .children(new ArrayList<>())
                .build();
            newRow.getChildren().add(cell);
        }
        stampRevisionTree(newRow, revisionId, author);
        return newRow;
    }

    private String estimateScope(int opCount, int blockCount) {
        if (blockCount >= 10 || opCount >= 20) return "major";
        if (blockCount >= 3  || opCount >= 5)  return "moderate";
        return "minor";
    }
}
