package io.docpilot.processor.converter;

import io.docpilot.processor.exception.ConversionException;
import io.docpilot.processor.model.*;
import lombok.extern.slf4j.Slf4j;
import org.docx4j.Docx4J;
import org.docx4j.convert.out.HTMLSettings;
import org.docx4j.model.structure.SectionWrapper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.StyleDefinitionsPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart;
import org.docx4j.wml.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Converts a DOCX file to HTML and extracts its {@link StyleRegistry}.
 *
 * <p>Uses docx4j's built-in XSL-based HTML exporter, which faithfully renders
 * paragraph styles, character styles, tables, and inline images (as base64).
 */
@Component
@Slf4j
public class DocxToHtmlConverter {

    /**
     * Converts the given DOCX input stream to HTML and extracts style metadata.
     *
     * @param in       DOCX input stream (caller is responsible for closing)
     * @param docId    pre-assigned UUID for this document
     * @param filename original filename, used for display / metadata only
     * @return a result containing the HTML string and the extracted StyleRegistry
     */
    public Result convert(InputStream in, String docId, String filename) {
        try {
            WordprocessingMLPackage wmlPackage = WordprocessingMLPackage.load(in);

            String html = exportToHtml(wmlPackage, docId);
            StyleRegistry registry = extractRegistry(wmlPackage, docId, filename);
            int wordCount = countWords(wmlPackage);

            log.info("DOCX→HTML complete: docId={} filename={} words={}", docId, filename, wordCount);
            return new Result(html, registry, wordCount);

        } catch (ConversionException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ConversionException("Failed to convert DOCX to HTML: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    //  HTML export
    // -----------------------------------------------------------------------

    private String exportToHtml(WordprocessingMLPackage wmlPackage, String docId) throws Exception {
        HTMLSettings htmlSettings = Docx4J.createHTMLSettings();
        htmlSettings.setOpcPackage(wmlPackage);
        // Embed images as base64 (no separate image files needed)
        htmlSettings.setImageDirPath(null);
        htmlSettings.setImageTargetUri(null);
        // Assign a unique CSS prefix to avoid collisions when multiple docs are rendered
        htmlSettings.setUserCSS("/* DocPilot docId=" + docId + " */\n");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Docx4J.toHTML(htmlSettings, baos, Docx4J.FLAG_EXPORT_PREFER_XSL);
        return baos.toString(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    //  Style registry extraction
    // -----------------------------------------------------------------------

    public StyleRegistry extractRegistry(WordprocessingMLPackage wmlPackage,
                                          String docId, String filename) {
        MainDocumentPart mdp = wmlPackage.getMainDocumentPart();

        Map<String, StyleEntry> styles = new LinkedHashMap<>();
        List<StyleEntry> customStyles = new ArrayList<>();

        StyleDefinitionsPart sdp = mdp.getStyleDefinitionsPart();
        if (sdp != null) {
            Styles xmlStyles = sdp.getJaxbElement();
            if (xmlStyles != null && xmlStyles.getStyle() != null) {
                for (Style style : xmlStyles.getStyle()) {
                    StyleEntry entry = toStyleEntry(style);
                    if (!style.isCustomStyle()) {
                        styles.put(style.getStyleId(), entry);
                    } else {
                        customStyles.add(entry);
                    }
                }
            }
        }

        PageLayout pageLayout = extractPageLayout(wmlPackage);
        List<Map<String, Object>> numberingDefs = extractNumberingDefinitions(mdp);
        DocDefaults defaults = extractDefaults(sdp);

        return StyleRegistry.builder()
            .docId(docId)
            .filename(filename)
            .extractedAt(Instant.now())
            .styles(styles)
            .customStyles(customStyles.isEmpty() ? null : customStyles)
            .pageLayout(pageLayout)
            .numberingDefinitions(numberingDefs.isEmpty() ? null : numberingDefs)
            .defaultFontAscii(defaults.font())
            .defaultFontSizePt(defaults.sizePt())
            .defaultFontColor(defaults.color())
            .build();
    }

    private StyleEntry toStyleEntry(Style style) {
        StyleEntry.StyleEntryBuilder b = StyleEntry.builder()
            .type(style.getType())
            .name(style.getName() != null ? style.getName().getVal() : null)
            .basedOn(style.getBasedOn() != null ? style.getBasedOn().getVal() : null);

        // Run properties
        RPr rpr = style.getRPr();
        if (rpr != null) {
            applyRunProps(b, rpr);
        }

        // Paragraph properties
        PPr ppr = style.getPPr();
        if (ppr != null) {
            applyParaProps(b, ppr);
        }

        // Table properties (for table styles)
        CTTblPrBase tblPr = style.getTblPr();
        if (tblPr != null && tblPr.getShd() != null) {
            b.bgColor(hexColor(tblPr.getShd().getFill()));
        }

        return b.build();
    }

    private void applyRunProps(StyleEntry.StyleEntryBuilder b, RPr rpr) {
        if (rpr.getRFonts() != null) {
            b.fontAscii(rpr.getRFonts().getAscii());
            b.fontEastAsia(rpr.getRFonts().getEastAsia());
        }
        if (rpr.getSz() != null && rpr.getSz().getVal() != null) {
            b.sizePt(halfPointsToPt(rpr.getSz().getVal()));
        }
        b.bold(isEnabled(rpr.getB()));
        b.italic(isEnabled(rpr.getI()));
        b.strikethrough(isEnabled(rpr.getStrike()));
        if (rpr.getU() != null && rpr.getU().getVal() != null) {
            b.underline(!rpr.getU().getVal().value().equals("none"));
        }
        if (rpr.getColor() != null) {
            b.color(hexColor(rpr.getColor().getVal()));
        }
        if (rpr.getHighlight() != null && rpr.getHighlight().getVal() != null) {
            b.highlight(rpr.getHighlight().getVal());
        }
        if (rpr.getSpacing() != null && rpr.getSpacing().getVal() != null) {
            b.characterSpacingPt(twentieathsToPoints(rpr.getSpacing().getVal()));
        }
    }

    private void applyParaProps(StyleEntry.StyleEntryBuilder b, PPr ppr) {
        if (ppr.getJc() != null && ppr.getJc().getVal() != null) {
            b.alignment(ppr.getJc().getVal().value());
        }
        if (ppr.getSpacing() != null) {
            PPrBase.Spacing sp = ppr.getSpacing();
            if (sp.getBefore() != null)      b.spacingBeforePt(twentieathsToPoints(sp.getBefore()));
            if (sp.getAfter() != null)       b.spacingAfterPt(twentieathsToPoints(sp.getAfter()));
            if (sp.getLine() != null)        b.lineSpacingPt(twentieathsToPoints(sp.getLine()));
        }
        if (ppr.getInd() != null) {
            PPrBase.Ind ind = ppr.getInd();
            if (ind.getLeft() != null)       b.indentLeftPt(twentieathsToPoints(ind.getLeft()));
            if (ind.getRight() != null)      b.indentRightPt(twentieathsToPoints(ind.getRight()));
            if (ind.getHanging() != null)    b.indentHangingPt(twentieathsToPoints(ind.getHanging()));
            if (ind.getFirstLine() != null)  b.indentFirstLinePt(twentieathsToPoints(ind.getFirstLine()));
        }
        if (ppr.getShd() != null && ppr.getShd().getFill() != null) {
            b.bgColor(hexColor(ppr.getShd().getFill()));
        }
    }

    // -----------------------------------------------------------------------
    //  Page layout
    // -----------------------------------------------------------------------

    private PageLayout extractPageLayout(WordprocessingMLPackage wmlPackage) {
        try {
            List<SectionWrapper> sections = wmlPackage.getDocumentModel().getSections();
            if (sections == null || sections.isEmpty()) return null;

            SectPr sectPr = sections.get(sections.size() - 1).getSectPr();
            if (sectPr == null) return null;

            PageLayout.PageLayoutBuilder pb = PageLayout.builder();

            if (sectPr.getPgSz() != null) {
                SectPr.PgSz pgSz = sectPr.getPgSz();
                double w = twentieathsToPoints(pgSz.getW());
                double h = twentieathsToPoints(pgSz.getH());
                pb.widthPt(w).heightPt(h);
                pb.orientation(w > h ? "landscape" : "portrait");
                pb.paperSize(guessStandardSize(w, h));
            }

            if (sectPr.getPgMar() != null) {
                SectPr.PgMar m = sectPr.getPgMar();
                pb.marginTopPt(bigTwentieathsToPoints(m.getTop()));
                pb.marginBottomPt(bigTwentieathsToPoints(m.getBottom()));
                pb.marginLeftPt(bigTwentieathsToPoints(m.getLeft()));
                pb.marginRightPt(bigTwentieathsToPoints(m.getRight()));
                pb.marginHeaderPt(bigTwentieathsToPoints(m.getHeader()));
                pb.marginFooterPt(bigTwentieathsToPoints(m.getFooter()));
            }

            if (sectPr.getCols() != null && sectPr.getCols().getNum() != null) {
                pb.columns(sectPr.getCols().getNum().intValue());
            } else {
                pb.columns(1);
            }

            return pb.build();
        } catch (Exception e) {
            log.warn("Could not extract page layout: {}", e.getMessage());
            return null;
        }
    }

    private String guessStandardSize(double widthPt, double heightPt) {
        // Subtract small tolerance (1pt) for rounding
        double minW = Math.min(widthPt, heightPt);
        double maxH = Math.max(widthPt, heightPt);
        // A4 = 595.28 x 841.89 pt; Letter = 612 x 792 pt
        if (near(maxH, 841.89) && near(minW, 595.28)) return "A4";
        if (near(maxH, 792) && near(minW, 612))       return "Letter";
        if (near(maxH, 1008) && near(minW, 612))      return "Legal";
        if (near(maxH, 1190.55) && near(minW, 841.89)) return "A3";
        return null;
    }

    private boolean near(double a, double b) { return Math.abs(a - b) < 2.0; }

    // -----------------------------------------------------------------------
    //  Numbering definitions (basic extraction)
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> extractNumberingDefinitions(MainDocumentPart mdp) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            NumberingDefinitionsPart ndp = mdp.getNumberingDefinitionsPart();
            if (ndp == null) return result;
            Numbering numbering = ndp.getJaxbElement();
            if (numbering == null) return result;

            for (Numbering.Num num : numbering.getNum()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("numId", num.getNumId());
                if (num.getAbstractNumId() != null) {
                    entry.put("abstractNumId", num.getAbstractNumId().getVal());
                }
                result.add(entry);
            }
        } catch (Exception e) {
            log.debug("Numbering extraction skipped: {}", e.getMessage());
        }
        return result;
    }

    // -----------------------------------------------------------------------
    //  Document defaults
    // -----------------------------------------------------------------------

    private record DocDefaults(String font, Double sizePt, String color) {}

    private DocDefaults extractDefaults(StyleDefinitionsPart sdp) {
        if (sdp == null) return new DocDefaults(null, null, null);
        try {
            org.docx4j.wml.DocDefaults dd = sdp.getJaxbElement().getDocDefaults();
            if (dd == null || dd.getRPrDefault() == null
                           || dd.getRPrDefault().getRPr() == null) {
                return new DocDefaults(null, null, null);
            }
            RPr rpr = dd.getRPrDefault().getRPr();
            String font  = rpr.getRFonts() != null ? rpr.getRFonts().getAscii() : null;
            Double size  = rpr.getSz() != null && rpr.getSz().getVal() != null
                           ? halfPointsToPt(rpr.getSz().getVal()) : null;
            String color = rpr.getColor() != null ? hexColor(rpr.getColor().getVal()) : null;
            return new DocDefaults(font, size, color);
        } catch (Exception e) {
            return new DocDefaults(null, null, null);
        }
    }

    // -----------------------------------------------------------------------
    //  Word count
    // -----------------------------------------------------------------------

    private int countWords(WordprocessingMLPackage wmlPackage) {
        try {
            Body body = wmlPackage.getMainDocumentPart().getJaxbElement().getBody();
            StringBuilder sb = new StringBuilder();
            for (Object obj : body.getContent()) {
                Object uw = org.docx4j.XmlUtils.unwrap(obj);
                if (uw instanceof P para) {
                    for (Object c : para.getContent()) {
                        Object uc = org.docx4j.XmlUtils.unwrap(c);
                        if (uc instanceof R run) {
                            for (Object rc : run.getContent()) {
                                Object urc = org.docx4j.XmlUtils.unwrap(rc);
                                if (urc instanceof Text t) sb.append(t.getValue()).append(' ');
                            }
                        }
                    }
                }
            }
            String text = sb.toString();
            return text.isBlank() ? 0 : text.trim().split("\\s+").length;
        } catch (Exception e) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    //  Unit conversion helpers
    // -----------------------------------------------------------------------

    /** OOXML half-points (sz) → points */
    private double halfPointsToPt(BigInteger halfPoints) {
        return halfPoints.doubleValue() / 2.0;
    }

    /** OOXML twentieths-of-a-point → points */
    private double twentieathsToPoints(BigInteger twips) {
        return twips == null ? 0 : twips.doubleValue() / 20.0;
    }

    private double twentieathsToPoints(long twips) { return twips / 20.0; }

    private double bigTwentieathsToPoints(BigInteger v) {
        return v == null ? 0 : v.doubleValue() / 20.0;
    }

    /** Return canonical hex colour, or null for "auto". */
    private String hexColor(String val) {
        if (val == null || "auto".equalsIgnoreCase(val)) return null;
        return val.startsWith("#") ? val : "#" + val;
    }

    /** BooleanDefaultTrue: null → not set; isVal() returns true by default when val absent. */
    private Boolean isEnabled(BooleanDefaultTrue flag) {
        if (flag == null) return null;
        return flag.isVal();
    }

    // -----------------------------------------------------------------------
    //  Result record
    // -----------------------------------------------------------------------

    public record Result(String html, StyleRegistry registry, int wordCount) {}
}
