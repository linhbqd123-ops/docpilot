package io.docpilot.processor.converter;

import io.docpilot.processor.exception.ConversionException;
import io.docpilot.processor.model.StyleEntry;
import io.docpilot.processor.model.StyleRegistry;
import lombok.extern.slf4j.Slf4j;
import org.docx4j.convert.in.xhtml.XHTMLImporterImpl;
import org.docx4j.model.structure.SectionWrapper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart;
import org.docx4j.openpackaging.parts.WordprocessingML.StyleDefinitionsPart;
import org.docx4j.wml.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Converts an (X)HTML string back to a DOCX binary.
 *
 * <p>When a {@link StyleRegistry} is provided, it:
 * <ol>
 *   <li>Applies the original page layout (margins, paper size).</li>
 *   <li>Overlays the stored paragraph/character styles onto the converted document's
 *       style definitions, so heading fonts, colours, and spacing are preserved.</li>
 * </ol>
 *
 * <p>Fidelity depends on the quality of the HTML; for best results always pass
 * the HTML produced by {@link DocxToHtmlConverter} together with its docId.
 */
@Component
@Slf4j
public class HtmlToDocxConverter {

    /**
     * @param html         valid XHTML / HTML content
     * @param baseUrl      optional base URL for resolving relative URLs in the HTML (may be null)
     * @param registry     optional style registry to restore original formatting (may be null)
     * @return raw DOCX bytes
     */
    public byte[] convert(String html, String baseUrl, StyleRegistry registry) {
        try {
            WordprocessingMLPackage wmlPackage = WordprocessingMLPackage.createPackage();

            // Ensure numbering definitions part exists (required by XHTMLImporter for lists)
            NumberingDefinitionsPart ndp = new NumberingDefinitionsPart();
            wmlPackage.getMainDocumentPart().addTargetPart(ndp);
            ndp.unmarshalDefaultNumbering();

            // Apply style registry BEFORE import so that the imported
            // content can reference the correct styles by ID
            if (registry != null) {
                applyStyleRegistry(wmlPackage, registry);
                applyPageLayout(wmlPackage, registry);
            }

            // Import HTML
            XHTMLImporterImpl importer = new XHTMLImporterImpl(wmlPackage);
            importer.setHyperlinkStyle("Hyperlink");
            List<Object> converted = importer.convert(html, baseUrl);
            wmlPackage.getMainDocumentPart().getContent().addAll(converted);

            // Export to DOCX bytes
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wmlPackage.save(baos);
            log.info("HTML→DOCX complete: {} bytes", baos.size());
            return baos.toByteArray();

        } catch (ConversionException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ConversionException("Failed to convert HTML to DOCX: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    //  Style overlay
    // -----------------------------------------------------------------------

    /**
     * Merges stored StyleEntry values into the package's StyleDefinitionsPart.
     * Only updates properties that are explicitly set in the registry (null = keep docx4j default).
     */
    private void applyStyleRegistry(WordprocessingMLPackage wmlPackage, StyleRegistry registry) {
        try {
            StyleDefinitionsPart sdp = wmlPackage.getMainDocumentPart().getStyleDefinitionsPart();
            if (sdp == null) return;

            Styles styles = sdp.getJaxbElement();
            if (styles == null) return;

            // Build index of existing styles for fast lookup
            Map<String, Style> existingById = new java.util.HashMap<>();
            for (Style s : styles.getStyle()) {
                existingById.put(s.getStyleId(), s);
            }

            for (Map.Entry<String, StyleEntry> entry : registry.getStyles().entrySet()) {
                String styleId = entry.getKey();
                StyleEntry se = entry.getValue();

                Style style = existingById.get(styleId);
                if (style == null) {
                    // Style doesn't exist yet — create it
                    style = createStyle(styleId, se);
                    styles.getStyle().add(style);
                } else {
                    // Overlay stored properties
                    overlayRunProps(style, se);
                    overlayParaProps(style, se);
                }
            }
        } catch (Exception e) {
            // Non-fatal: the document will still be produced, just without custom styling
            log.warn("Could not apply style registry: {}", e.getMessage());
        }
    }

    private Style createStyle(String styleId, StyleEntry se) {
        ObjectFactory factory = new ObjectFactory();
        Style style = factory.createStyle();
        style.setStyleId(styleId);
        style.setType("paragraph");

        Style.Name nameEl = factory.createStyleName();
        nameEl.setVal(se.getName() != null ? se.getName() : styleId);
        style.setName(nameEl);

        overlayRunProps(style, se);
        overlayParaProps(style, se);
        return style;
    }

    private void overlayRunProps(Style style, StyleEntry se) {
        if (!hasRunProps(se)) return;
        if (style.getRPr() == null) style.setRPr(new RPr());
        RPr rpr = style.getRPr();

        if (se.getFontAscii() != null || se.getFontEastAsia() != null) {
            if (rpr.getRFonts() == null) rpr.setRFonts(new RFonts());
            if (se.getFontAscii() != null) rpr.getRFonts().setAscii(se.getFontAscii());
            if (se.getFontEastAsia() != null) rpr.getRFonts().setEastAsia(se.getFontEastAsia());
        }
        if (se.getSizePt() != null) {
            HpsMeasure sz = new HpsMeasure();
            sz.setVal(BigInteger.valueOf(Math.round(se.getSizePt() * 2)));
            rpr.setSz(sz);
            rpr.setSzCs(sz);
        }
        if (se.getBold() != null) {
            rpr.setB(boolFlag(se.getBold()));
        }
        if (se.getItalic() != null) {
            rpr.setI(boolFlag(se.getItalic()));
        }
        if (se.getColor() != null) {
            Color c = new Color();
            c.setVal(se.getColor().replace("#", ""));
            rpr.setColor(c);
        }
    }

    private void overlayParaProps(Style style, StyleEntry se) {
        if (!hasParaProps(se)) return;
        if (style.getPPr() == null) style.setPPr(new PPr());
        PPr ppr = style.getPPr();

        if (se.getAlignment() != null) {
            Jc jc = new Jc();
            try { jc.setVal(JcEnumeration.fromValue(se.getAlignment())); } catch (Exception ignored) {}
            ppr.setJc(jc);
        }
        if (se.getSpacingBeforePt() != null || se.getSpacingAfterPt() != null
                || se.getLineSpacingPt() != null) {
            if (ppr.getSpacing() == null) ppr.setSpacing(new PPrBase.Spacing());
            PPrBase.Spacing sp = ppr.getSpacing();
            if (se.getSpacingBeforePt() != null)
                sp.setBefore(BigInteger.valueOf(Math.round(se.getSpacingBeforePt() * 20)));
            if (se.getSpacingAfterPt() != null)
                sp.setAfter(BigInteger.valueOf(Math.round(se.getSpacingAfterPt() * 20)));
            if (se.getLineSpacingPt() != null)
                sp.setLine(BigInteger.valueOf(Math.round(se.getLineSpacingPt() * 20)));
        }
    }

    // -----------------------------------------------------------------------
    //  Page layout overlay
    // -----------------------------------------------------------------------

    private void applyPageLayout(WordprocessingMLPackage wmlPackage, StyleRegistry registry) {
        io.docpilot.processor.model.PageLayout pl = registry.getPageLayout();
        if (pl == null) return;
        try {
            List<SectionWrapper> sections = wmlPackage.getDocumentModel().getSections();
            if (sections == null || sections.isEmpty()) return;
            SectPr sectPr = sections.get(0).getSectPr();
            if (sectPr == null) return;

            if (pl.getWidthPt() != null && pl.getHeightPt() != null) {
                SectPr.PgSz pgSz = new SectPr.PgSz();
                pgSz.setW(BigInteger.valueOf(Math.round(pl.getWidthPt() * 20)));
                pgSz.setH(BigInteger.valueOf(Math.round(pl.getHeightPt() * 20)));
                if ("landscape".equalsIgnoreCase(pl.getOrientation())) {
                    pgSz.setOrient(STPageOrientation.LANDSCAPE);
                }
                sectPr.setPgSz(pgSz);
            }

            if (pl.getMarginTopPt() != null) {
                SectPr.PgMar mar = new SectPr.PgMar();
                mar.setTop(BigInteger.valueOf(Math.round(pl.getMarginTopPt() * 20)));
                mar.setBottom(BigInteger.valueOf(Math.round(
                    pl.getMarginBottomPt() != null ? pl.getMarginBottomPt() * 20 : 1440)));
                mar.setLeft(BigInteger.valueOf(Math.round(
                    pl.getMarginLeftPt() != null ? pl.getMarginLeftPt() * 20 : 1440)));
                mar.setRight(BigInteger.valueOf(Math.round(
                    pl.getMarginRightPt() != null ? pl.getMarginRightPt() * 20 : 1440)));
                sectPr.setPgMar(mar);
            }
        } catch (Exception e) {
            log.warn("Could not apply page layout: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private BooleanDefaultTrue boolFlag(boolean value) {
        BooleanDefaultTrue flag = new BooleanDefaultTrue();
        flag.setVal(Boolean.valueOf(value));
        return flag;
    }

    private boolean hasRunProps(StyleEntry se) {
        return se.getFontAscii() != null || se.getFontEastAsia() != null
            || se.getSizePt() != null || se.getBold() != null
            || se.getItalic() != null || se.getColor() != null;
    }

    private boolean hasParaProps(StyleEntry se) {
        return se.getAlignment() != null || se.getSpacingBeforePt() != null
            || se.getSpacingAfterPt() != null || se.getLineSpacingPt() != null;
    }
}
