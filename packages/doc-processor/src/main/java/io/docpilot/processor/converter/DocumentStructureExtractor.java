package io.docpilot.processor.converter;

import io.docpilot.processor.exception.ConversionException;
import io.docpilot.processor.model.DocumentNode;
import io.docpilot.processor.model.DocumentStructure;
import io.docpilot.processor.model.PageLayout;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.docx4j.XmlUtils;
import org.docx4j.model.structure.SectionWrapper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.*;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds a {@link DocumentStructure} (outline + statistics) from a DOCX file.
 *
 * <p>The outline is a depth-first hierarchical tree where headings form the
 * skeleton and non-heading paragraphs are attached as children of the innermost
 * heading above them.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DocumentStructureExtractor {

    private final DocxToHtmlConverter htmlConverter; // reuses page layout extraction

    public DocumentStructure extract(InputStream in, String docId, String filename) {
        try {
            WordprocessingMLPackage wml = WordprocessingMLPackage.load(in);
            Body body = wml.getMainDocumentPart().getJaxbElement().getBody();

            AtomicInteger idCounter  = new AtomicInteger(0);
            AtomicInteger wordCounter  = new AtomicInteger(0);
            AtomicInteger paraCounter  = new AtomicInteger(0);
            AtomicInteger tableCounter = new AtomicInteger(0);
            AtomicInteger imageCounter = new AtomicInteger(0);

            List<DocumentNode> flat = new ArrayList<>(); // flat list before tree-building

            for (Object obj : body.getContent()) {
                Object uw = XmlUtils.unwrap(obj);

                if (uw instanceof P para) {
                    paraCounter.incrementAndGet();
                    wordCounter.addAndGet(wordCount(para));
                    imageCounter.addAndGet(imageCount(para));
                    DocumentNode node = paraToNode(para, idCounter);
                    if (node != null) flat.add(node);

                } else if (uw instanceof Tbl table) {
                    tableCounter.incrementAndGet();
                    flat.add(DocumentNode.builder()
                        .id("tbl_" + idCounter.incrementAndGet())
                        .type("table")
                        .text(summarizeTable(table))
                        .wordCount(tableWordCount(table))
                        .build());
                }
            }

            // Build hierarchical tree
            List<DocumentNode> outline = buildTree(flat);

            // Page layout (reuse helper)
            PageLayout pageLayout = htmlConverter.extractRegistry(wml, docId, filename).getPageLayout();

            // Page count estimate: ~300 words per page
            int estPages = Math.max(1, (int) Math.ceil(wordCounter.get() / 300.0));

            // Doc type heuristic
            String docType = detectDocType(flat);

            DocumentStructure structure = DocumentStructure.builder()
                .docId(docId)
                .filename(filename)
                .extractedAt(Instant.now())
                .pageCount(estPages)
                .wordCount(wordCounter.get())
                .paragraphCount(paraCounter.get())
                .tableCount(tableCounter.get())
                .imageCount(imageCounter.get())
                .docType(docType)
                .outline(outline)
                .sections(extractSections(wml))
                .build();

            log.info("Structure extracted: docId={} pages={} words={}", docId, estPages, wordCounter.get());
            return structure;

        } catch (Exception e) {
            throw new ConversionException("Failed to extract document structure: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    //  Node creation
    // -----------------------------------------------------------------------

    private DocumentNode paraToNode(P para, AtomicInteger idCounter) {
        String styleId = getStyleId(para);
        String text    = getText(para, 500);
        int words      = wordCount(para);

        if (text.isBlank()) return null;

        String type;
        Integer level = null;

        if (styleId != null && styleId.toLowerCase().startsWith("heading")) {
            type  = "heading";
            level = headingLevel(styleId);
        } else if (para.getPPr() != null && para.getPPr().getNumPr() != null) {
            type = "list";
        } else {
            type = "paragraph";
        }

        return DocumentNode.builder()
            .id("p_" + idCounter.incrementAndGet())
            .type(type)
            .level(level)
            .text(text)
            .styleId(styleId)
            .wordCount(words)
            .children(new ArrayList<>())
            .build();
    }

    // -----------------------------------------------------------------------
    //  Tree builder
    // -----------------------------------------------------------------------

    /**
     * Converts a flat list of nodes into a hierarchical tree.
     * Headings at level N "own" everything below them until the next heading
     * at level ≤ N.
     */
    private List<DocumentNode> buildTree(List<DocumentNode> flat) {
        List<DocumentNode> roots = new ArrayList<>();
        Deque<DocumentNode> stack = new ArrayDeque<>(); // heading stack

        for (DocumentNode node : flat) {
            if ("heading".equals(node.getType()) && node.getLevel() != null) {
                int lvl = node.getLevel();
                // Pop headings of equal or deeper level
                while (!stack.isEmpty()) {
                    DocumentNode top = stack.peek();
                    if ("heading".equals(top.getType()) && top.getLevel() != null
                            && top.getLevel() >= lvl) {
                        stack.pop();
                    } else break;
                }
                if (stack.isEmpty()) {
                    roots.add(node);
                } else {
                    stack.peek().getChildren().add(node);
                }
                stack.push(node);
            } else {
                // Non-heading: attach to innermost heading, or root
                if (stack.isEmpty()) {
                    roots.add(node);
                } else {
                    stack.peek().getChildren().add(node);
                }
            }
        }
        return roots;
    }

    // -----------------------------------------------------------------------
    //  Section extraction
    // -----------------------------------------------------------------------

    private List<DocumentStructure.SectionInfo> extractSections(WordprocessingMLPackage wml) {
        try {
            List<SectionWrapper> sections = wml.getDocumentModel().getSections();
            if (sections == null) return List.of();

            List<DocumentStructure.SectionInfo> result = new ArrayList<>();
            for (int i = 0; i < sections.size(); i++) {
                SectPr sectPr = sections.get(i).getSectPr();
                PageLayout layout = sectPrToLayout(sectPr);
                result.add(DocumentStructure.SectionInfo.builder()
                    .sectionIndex(i + 1)
                    .layout(layout)
                    .build());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private PageLayout sectPrToLayout(SectPr sectPr) {
        if (sectPr == null) return null;
        PageLayout.PageLayoutBuilder pb = PageLayout.builder();
        if (sectPr.getPgSz() != null) {
            SectPr.PgSz pgSz = sectPr.getPgSz();
            double w = pgSz.getW() != null ? pgSz.getW().doubleValue() / 20.0 : 0;
            double h = pgSz.getH() != null ? pgSz.getH().doubleValue() / 20.0 : 0;
            pb.widthPt(w).heightPt(h).orientation(w > h ? "landscape" : "portrait");
        }
        return pb.build();
    }

    // -----------------------------------------------------------------------
    //  Heuristic doc type detection
    // -----------------------------------------------------------------------

    private String detectDocType(List<DocumentNode> flat) {
        long headings = flat.stream().filter(n -> "heading".equals(n.getType())).count();
        boolean hasAbstract  = flat.stream().anyMatch(n -> containsWord(n.getText(), "abstract"));
        boolean hasWhereas   = flat.stream().anyMatch(n -> containsWord(n.getText(), "whereas"));
        boolean hasSection   = flat.stream().anyMatch(n -> containsWord(n.getText(), "section")
                                                        || containsWord(n.getText(), "article"));
        boolean hasFigure    = flat.stream().anyMatch(n -> containsWord(n.getText(), "figure")
                                                        || containsWord(n.getText(), "table"));

        if (hasAbstract && hasFigure && headings > 4)  return "academic";
        if (hasWhereas || hasSection)                  return "contract";
        if (headings > 6 && hasFigure)                 return "report";
        if (headings > 3)                              return "structured";
        return "general";
    }

    private boolean containsWord(String text, String word) {
        if (text == null) return false;
        return text.toLowerCase().contains(word.toLowerCase());
    }

    // -----------------------------------------------------------------------
    //  Text / stat helpers
    // -----------------------------------------------------------------------

    private String getStyleId(P para) {
        if (para.getPPr() == null || para.getPPr().getPStyle() == null) return null;
        return para.getPPr().getPStyle().getVal();
    }

    private int headingLevel(String styleId) {
        try { return Integer.parseInt(styleId.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 1; }
    }

    private String getText(P para, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (Object obj : para.getContent()) {
            Object uw = XmlUtils.unwrap(obj);
            if (uw instanceof R r) {
                for (Object c : r.getContent()) {
                    Object uwc = XmlUtils.unwrap(c);
                    if (uwc instanceof Text t) sb.append(t.getValue());
                }
            } else if (uw instanceof P.Hyperlink link) {
                for (Object item : link.getContent()) {
                    Object uItem = XmlUtils.unwrap(item);
                    if (uItem instanceof R r) {
                        for (Object c : r.getContent()) {
                            Object uwc = XmlUtils.unwrap(c);
                            if (uwc instanceof Text t) sb.append(t.getValue());
                        }
                    }
                }
            }
            if (sb.length() >= maxChars) break;
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars) + "…" : sb.toString();
    }

    private int wordCount(P para) {
        String text = getText(para, Integer.MAX_VALUE);
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    private int tableWordCount(Tbl table) {
        return table.getContent().stream()
            .map(XmlUtils::unwrap)
            .filter(r -> r instanceof Tr)
            .flatMap(r -> ((Tr) r).getContent().stream())
            .map(XmlUtils::unwrap)
            .filter(c -> c instanceof Tc)
            .flatMap(c -> ((Tc) c).getContent().stream())
            .map(XmlUtils::unwrap)
            .filter(p -> p instanceof P)
            .mapToInt(p -> wordCount((P) p))
            .sum();
    }

    private int imageCount(P para) {
        return (int) para.getContent().stream()
            .map(XmlUtils::unwrap)
            .filter(r -> r instanceof R)
            .flatMap(r -> ((R) r).getContent().stream())
            .map(XmlUtils::unwrap)
            .filter(c -> c instanceof Drawing || c instanceof Pict)
            .count();
    }

    private String summarizeTable(Tbl table) {
        long rows = table.getContent().stream()
            .map(XmlUtils::unwrap)
            .filter(r -> r instanceof Tr)
            .count();
        // Count columns from first row
        Object firstRow = table.getContent().isEmpty() ? null
            : XmlUtils.unwrap(table.getContent().get(0));
        long cols = (firstRow instanceof Tr tr) ? tr.getContent().stream()
            .map(XmlUtils::unwrap).filter(c -> c instanceof Tc).count() : 0;
        return "[Table " + rows + "×" + cols + "]";
    }
}
