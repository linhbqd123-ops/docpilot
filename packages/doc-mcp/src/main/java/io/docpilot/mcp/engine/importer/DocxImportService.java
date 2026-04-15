package io.docpilot.mcp.engine.importer;

import io.docpilot.mcp.converter.DocxToHtmlConverter;
import io.docpilot.mcp.engine.anchor.AnchorService;
import io.docpilot.mcp.model.document.*;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.model.session.SessionState;
import io.docpilot.mcp.storage.RegistryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.docx4j.XmlUtils;
import org.docx4j.model.structure.SectionWrapper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart;
import org.docx4j.openpackaging.parts.WordprocessingML.StyleDefinitionsPart;
import org.docx4j.wml.*;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Imports a DOCX file and builds a canonical {@link DocumentSession} with a
 * stable-anchored component tree.
 *
 * <p>The resulting tree structure mirrors the plan specification (section 8.2):
 * DOCUMENT → SECTION(s) → [HEADING | PARAGRAPH | TABLE | LIST | IMAGE | …]
 * TABLE → TABLE_ROW(s) → TABLE_CELL(s) → [PARAGRAPH | …]
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocxImportService {

    private final AnchorService anchorService;
    private final DocxToHtmlConverter docxToHtmlConverter;
    private final RegistryStore registryStore;

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    /**
     * Loads a DOCX from the supplied stream and returns a fully anchored session.
     *
     * @param in          DOCX input stream (caller is responsible for closing)
     * @param sessionId   pre-generated session UUID
     * @param docId       pre-generated document artefact UUID
     * @param filename    display filename
     */
    public DocumentSession importDocx(InputStream in,
                                      String sessionId,
                                      String docId,
                                      String filename) throws Exception {
        WordprocessingMLPackage wml = WordprocessingMLPackage.load(in);
        Body body = wml.getMainDocumentPart().getJaxbElement().getBody();

        // ── Counters ──────────────────────────────────────────────────────
        AtomicInteger wordCount      = new AtomicInteger(0);
        AtomicInteger paragraphCount = new AtomicInteger(0);
        AtomicInteger tableCount     = new AtomicInteger(0);
        AtomicInteger imageCount     = new AtomicInteger(0);
        AtomicInteger sectionCount   = new AtomicInteger(0);

        // ── Root DOCUMENT node ────────────────────────────────────────────
        String docPath = "document";
        DocumentComponent docNode = DocumentComponent.builder()
            .id(UUID.randomUUID().toString())
            .type(ComponentType.DOCUMENT)
            .anchor(anchorService.generate(ComponentType.DOCUMENT, docPath, filename, null, ""))
            .children(new ArrayList<>())
            .build();
        docNode.getAnchor(); // ensure non-null after builder

        // ── Section detection via SectionWrapper ─────────────────────────
        // docx4j tracks section properties in paragraph-level <w:pPr><w:sectPr>
        // and a final <w:sectPr> at body level. We group body blocks by section.
        List<List<Object>> sections = groupBySections(body, wml);

        // ── Walk each section ─────────────────────────────────────────────
        Map<String, Integer> secTypeCounters = new HashMap<>();
        for (int sIdx = 0; sIdx < sections.size(); sIdx++) {
            sectionCount.incrementAndGet();
            String sectionPath = "document/section[" + (sIdx + 1) + "]";

            DocumentComponent sectionNode = DocumentComponent.builder()
                .id(UUID.randomUUID().toString())
                .type(ComponentType.SECTION)
                .parentId(docNode.getId())
                .anchor(anchorService.generate(ComponentType.SECTION, sectionPath, "", null, docPath))
                .children(new ArrayList<>())
                .build();
            docNode.getChildren().add(sectionNode);

            Map<String, Integer> typeCounters = new HashMap<>();

            for (Object obj : sections.get(sIdx)) {
                Object uw = XmlUtils.unwrap(obj);

                if (uw instanceof P para) {
                    DocumentComponent block = buildParagraphNode(
                        para, wml, sectionNode.getId(), sectionPath, typeCounters,
                        wordCount, imageCount);
                    if (block != null) {
                        paragraphCount.incrementAndGet();
                        if (block.getType() == ComponentType.IMAGE) imageCount.incrementAndGet();
                        sectionNode.getChildren().add(block);
                    }

                } else if (uw instanceof Tbl tbl) {
                    tableCount.incrementAndGet();
                    int tblIdx = typeCounters.merge("table", 1, Integer::sum);
                    String tblPath = sectionPath + "/table[" + tblIdx + "]";
                    DocumentComponent tblNode = buildTableNode(
                        tbl, wml, sectionNode.getId(), sectionPath, tblPath,
                        wordCount, paragraphCount);
                    sectionNode.getChildren().add(tblNode);
                }
            }
        }

        // ── Assemble session ──────────────────────────────────────────────
        DocumentSession session = DocumentSession.builder()
            .sessionId(sessionId)
            .docId(docId)
            .filename(filename)
            .originalFilename(filename)
            .root(docNode)
            .state(SessionState.READY)
            .createdAt(Instant.now())
            .lastModifiedAt(Instant.now())
            .wordCount(wordCount.get())
            .paragraphCount(paragraphCount.get())
            .tableCount(tableCount.get())
            .imageCount(imageCount.get())
            .sectionCount(sectionCount.get())
            .build();

        registryStore.saveRegistry(docxToHtmlConverter.extractRegistry(wml, docId, filename));

        log.info("DOCX imported: sessionId={} docId={} words={} paragraphs={} tables={}",
            sessionId, docId, wordCount.get(), paragraphCount.get(), tableCount.get());
        return session;
    }

    // -----------------------------------------------------------------------
    //  Section grouping
    // -----------------------------------------------------------------------

    /**
     * Splits the body content into sections based on {@code <w:sectPr>} markers.
     * All content goes into at least one section.
     */
    private List<List<Object>> groupBySections(Body body, WordprocessingMLPackage wml) {
        List<List<Object>> sections = new ArrayList<>();
        List<Object> current = new ArrayList<>();
        sections.add(current);

        for (Object obj : body.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof P para) {
                PPr ppr = para.getPPr();
                if (ppr != null && ppr.getSectPr() != null) {
                    // This paragraph carries a section break — start a new section
                    current.add(obj); // include the paragraph itself in current section
                    current = new ArrayList<>();
                    sections.add(current);
                    continue;
                }
            }
            current.add(obj);
        }
        // Remove trailing empty sections (artifact of section-break detection)
        sections.removeIf(List::isEmpty);
        if (sections.isEmpty()) sections.add(new ArrayList<>());
        return sections;
    }

    // -----------------------------------------------------------------------
    //  Paragraph / run building
    // -----------------------------------------------------------------------

    private DocumentComponent buildParagraphNode(P para,
                                                  WordprocessingMLPackage wml,
                                                  String parentId,
                                                  String parentPath,
                                                  Map<String, Integer> typeCounters,
                                                  AtomicInteger wordCount,
                                                  AtomicInteger imageCount) {
        String styleId = getParagraphStyleId(para);
        int headingLevel = headingLevelOf(styleId, para, wml);
        ComponentType type = headingLevel > 0 ? ComponentType.HEADING : ComponentType.PARAGRAPH;

        // Check for image-only paragraph
        boolean hasImage = containsImage(para);
        if (hasImage) type = ComponentType.IMAGE;

        // Check for list paragraph
        boolean isList = isListParagraph(para);
        if (isList) type = ComponentType.LIST_ITEM;

        String typeKey = type.name().toLowerCase();
        int idx = typeCounters.merge(typeKey, 1, Integer::sum);
        String logicalPath = parentPath + "/" + typeKey + "[" + idx + "]";

        String fullText = extractText(para);
        wordCount.addAndGet(countWordsInText(fullText));

        LayoutProps layout = buildLayoutProps(para, wml, headingLevel);
        ContentProps content = ContentProps.builder().text(fullText).build();

        DocumentComponent node = DocumentComponent.builder()
            .id(UUID.randomUUID().toString())
            .type(type)
            .parentId(parentId)
            .styleRef(StyleRef.builder().styleId(styleId).build())
            .layoutProps(layout)
            .contentProps(content)
            .anchor(anchorService.generate(type, logicalPath, fullText, styleId, parentPath))
            .children(new ArrayList<>())
            .build();

        // Build TEXT_RUN children for the paragraph
        List<DocumentComponent> runs = buildRunChildren(para, node.getId(), logicalPath);
        node.setChildren(runs);

        return node;
    }

    private List<DocumentComponent> buildRunChildren(P para, String parentId, String parentPath) {
        List<DocumentComponent> runs = new ArrayList<>();
        Map<String, Integer> runCounters = new HashMap<>();

        for (Object obj : para.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof R run) {
                String runText = extractRunText(run);
                if (runText.isEmpty()) continue;

                int rIdx = runCounters.merge("text_run", 1, Integer::sum);
                String runPath = parentPath + "/text_run[" + rIdx + "]";

                LayoutProps runLayout = buildRunLayoutProps(run.getRPr());
                ContentProps content = ContentProps.builder().text(runText).build();

                DocumentComponent runNode = DocumentComponent.builder()
                    .id(UUID.randomUUID().toString())
                    .type(ComponentType.TEXT_RUN)
                    .parentId(parentId)
                    .layoutProps(runLayout)
                    .contentProps(content)
                    .anchor(anchorService.generate(ComponentType.TEXT_RUN, runPath, runText, null, parentPath))
                    .children(new ArrayList<>())
                    .build();
                runs.add(runNode);
            }
        }
        return runs;
    }

    // -----------------------------------------------------------------------
    //  Table building
    // -----------------------------------------------------------------------

    private DocumentComponent buildTableNode(Tbl tbl,
                                              WordprocessingMLPackage wml,
                                              String parentId,
                                              String parentPath,
                                              String tblPath,
                                              AtomicInteger wordCount,
                                              AtomicInteger paragraphCount) {
        String tableId = UUID.randomUUID().toString();
        String tableFingerprint = computeTableFingerprint(tbl);

        DocumentComponent tblNode = DocumentComponent.builder()
            .id(tableId)
            .type(ComponentType.TABLE)
            .parentId(parentId)
            .anchor(anchorService.generate(ComponentType.TABLE, tblPath, "", null, parentPath))
            .children(new ArrayList<>())
            .build();

        List<Object> rows = tbl.getContent();
        int rowIdx = 0;
        for (Object rowObj : rows) {
            Object uwRow = XmlUtils.unwrap(rowObj);
            if (!(uwRow instanceof Tr tr)) continue;

            int rowIdxFinal = rowIdx;
            String rowPath = tblPath + "/row[" + (rowIdx + 1) + "]";
            String rowId = UUID.randomUUID().toString();

            DocumentComponent rowNode = DocumentComponent.builder()
                .id(rowId)
                .type(ComponentType.TABLE_ROW)
                .parentId(tableId)
                .anchor(anchorService.generate(ComponentType.TABLE_ROW, rowPath, "", null, tblPath))
                .children(new ArrayList<>())
                .build();

            int colIdx = 0;
            for (Object cellObj : tr.getContent()) {
                Object uwCell = XmlUtils.unwrap(cellObj);
                if (!(uwCell instanceof Tc tc)) continue;

                String cellPath = rowPath + "/cell[" + (colIdx + 1) + "]";
                String cellText = extractTableCellText(tc);
                wordCount.addAndGet(countWordsInText(cellText));

                io.docpilot.mcp.model.document.Anchor cellAnchor =
                    anchorService.generateCellAnchor(
                        cellPath, cellText, null, rowPath,
                        tableId, rowId, rowIdxFinal, colIdx, tableFingerprint);

                DocumentComponent cellNode = DocumentComponent.builder()
                    .id(cellAnchor.getStableId())
                    .type(ComponentType.TABLE_CELL)
                    .parentId(rowId)
                    .anchor(cellAnchor)
                    .contentProps(ContentProps.builder().text(cellText).build())
                    .children(new ArrayList<>())
                    .build();

                // Paragraphs inside the cell
                Map<String, Integer> cellTypeCounters = new HashMap<>();
                for (Object cellContent : tc.getContent()) {
                    Object uwCellContent = XmlUtils.unwrap(cellContent);
                    if (uwCellContent instanceof P cellPara) {
                        DocumentComponent cellParaNode = buildParagraphNode(
                            cellPara, wml, cellNode.getId(), cellPath, cellTypeCounters,
                            wordCount, new AtomicInteger(0));
                        if (cellParaNode != null) {
                            paragraphCount.incrementAndGet();
                            cellNode.getChildren().add(cellParaNode);
                        }
                    }
                }
                rowNode.getChildren().add(cellNode);
                colIdx++;
            }
            tblNode.getChildren().add(rowNode);
            rowIdx++;
        }
        return tblNode;
    }

    // -----------------------------------------------------------------------
    //  Layout extraction helpers
    // -----------------------------------------------------------------------

    private LayoutProps buildLayoutProps(P para, WordprocessingMLPackage wml, int headingLevel) {
        PPr ppr = para.getPPr();
        if (ppr == null) return LayoutProps.builder().headingLevel(headingLevel > 0 ? headingLevel : null).build();

        LayoutProps.LayoutPropsBuilder b = LayoutProps.builder();
        b.headingLevel(headingLevel > 0 ? headingLevel : null);

        if (ppr.getJc() != null) b.alignment(ppr.getJc().getVal().value().toUpperCase());

        PPrBase.Ind ind = ppr.getInd();
        if (ind != null) {
            if (ind.getLeft() != null) b.indentLeft(ind.getLeft().intValue());
            if (ind.getRight() != null) b.indentRight(ind.getRight().intValue());
            if (ind.getFirstLine() != null) b.indentFirstLine(ind.getFirstLine().intValue());
        }

        PPrBase.Spacing spacing = ppr.getSpacing();
        if (spacing != null) {
            if (spacing.getBefore() != null) b.spacingBefore(spacing.getBefore().intValue());
            if (spacing.getAfter() != null) b.spacingAfter(spacing.getAfter().intValue());
            if (spacing.getLine() != null) b.lineSpacing(spacing.getLine().intValue());
        }

        // List level
        PPrBase.NumPr numPr = ppr.getNumPr();
        if (numPr != null && numPr.getIlvl() != null) {
            b.listLevel(numPr.getIlvl().getVal().intValue());
        }
        return b.build();
    }

    private LayoutProps buildRunLayoutProps(RPr rpr) {
        if (rpr == null) return null;
        LayoutProps.LayoutPropsBuilder b = LayoutProps.builder();

        if (rpr.getB() != null) b.bold(Boolean.TRUE);
        if (rpr.getI() != null) b.italic(Boolean.TRUE);
        if (rpr.getU() != null) b.underline(Boolean.TRUE);
        if (rpr.getStrike() != null) b.strikethrough(Boolean.TRUE);

        if (rpr.getRFonts() != null) {
            b.fontAscii(rpr.getRFonts().getAscii());
            b.fontHAnsi(rpr.getRFonts().getHAnsi());
        }
        if (rpr.getSz() != null) {
            b.fontSizePt(rpr.getSz().getVal().doubleValue() / 2.0);
        }
        if (rpr.getColor() != null && rpr.getColor().getVal() != null) {
            b.color(rpr.getColor().getVal());
        }
        return b.build();
    }

    // -----------------------------------------------------------------------
    //  Text extraction helpers
    // -----------------------------------------------------------------------

    private String extractText(P para) {
        StringBuilder sb = new StringBuilder();
        for (Object obj : para.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof R run) sb.append(extractRunText(run));
        }
        return sb.toString();
    }

    private String extractRunText(R run) {
        StringBuilder sb = new StringBuilder();
        for (Object obj : run.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof Text t) {
                sb.append(t.getValue());
            } else if (uw instanceof Br) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String extractTableCellText(Tc tc) {
        StringBuilder sb = new StringBuilder();
        for (Object obj : tc.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof P para) {
                String t = extractText(para);
                if (!t.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(t); }
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    //  Style / heading helpers
    // -----------------------------------------------------------------------

    private String getParagraphStyleId(P para) {
        if (para.getPPr() != null && para.getPPr().getPStyle() != null) {
            return para.getPPr().getPStyle().getVal();
        }
        return "Normal";
    }

    private int headingLevelOf(String styleId, P para, WordprocessingMLPackage wml) {
        if (styleId == null) return 0;
        String sid = styleId.toLowerCase();
        if (sid.startsWith("heading")) {
            try { return Integer.parseInt(sid.replace("heading", "").trim()); }
            catch (NumberFormatException ignored) {}
        }
        // Try outline level
        if (para.getPPr() != null && para.getPPr().getOutlineLvl() != null) {
            int ol = para.getPPr().getOutlineLvl().getVal().intValue();
            if (ol >= 0 && ol <= 8) return ol + 1;
        }
        return 0;
    }

    private boolean containsImage(P para) {
        for (Object obj : para.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof R run) {
                for (Object c : run.getContent()) {
                    Object uwc = XmlUtils.unwrap(c);
                    if (uwc instanceof Drawing || uwc instanceof Pict) return true;
                }
            }
        }
        return false;
    }

    private boolean isListParagraph(P para) {
        if (para.getPPr() != null && para.getPPr().getNumPr() != null) {
            PPrBase.NumPr numPr = para.getPPr().getNumPr();
            return numPr.getNumId() != null
                && !BigInteger.ZERO.equals(numPr.getNumId().getVal());
        }
        return false;
    }

    // -----------------------------------------------------------------------
    //  Table fingerprint
    // -----------------------------------------------------------------------

    private String computeTableFingerprint(Tbl tbl) {
        int rows = 0, cols = 0;
        for (Object obj : tbl.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof Tr tr) {
                rows++;
                int c = (int) tr.getContent().stream()
                    .filter(o -> XmlUtils.unwrap(o) instanceof Tc).count();
                if (c > cols) cols = c;
            }
        }
        return "tbl_r" + rows + "_c" + cols;
    }

    // -----------------------------------------------------------------------
    //  Word count
    // -----------------------------------------------------------------------

    private int countWordsInText(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }
}
