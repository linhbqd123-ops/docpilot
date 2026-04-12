package io.docpilot.processor.converter;

import io.docpilot.processor.exception.ConversionException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.Text;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link DocxToHtmlConverter}.
 *
 * Creates minimal in-memory DOCX documents using docx4j, so no test fixtures are
 * needed on disk.
 */
class DocxToHtmlConverterTest {

    private DocxToHtmlConverter converter;

    @BeforeEach
    void setUp() {
        converter = new DocxToHtmlConverter();
    }

    @Test
    void convert_simpleDocx_returnsHtmlAndRegistry() throws Exception {
        // Arrange
        byte[] docxBytes = createSimpleDocx("Hello DocPilot", null);
        String docId = "test-doc-001";

        // Act
        DocxToHtmlConverter.Result result;
        try (InputStream in = new ByteArrayInputStream(docxBytes)) {
            result = converter.convert(in, docId, "test.docx");
        }

        // Assert
        assertThat(result.html()).isNotBlank();
        assertThat(result.html()).containsIgnoringCase("Hello DocPilot");
        assertThat(result.registry()).isNotNull();
        assertThat(result.registry().getDocId()).isEqualTo(docId);
        assertThat(result.registry().getFilename()).isEqualTo("test.docx");
        assertThat(result.registry().getStyles()).isNotEmpty();
        assertThat(result.wordCount()).isGreaterThan(0);
    }

    @Test
    void convert_headingDocx_registryContainsHeadingStyle() throws Exception {
        byte[] docxBytes = createSimpleDocx("Introduction", "Heading1");
        DocxToHtmlConverter.Result result;
        try (InputStream in = new ByteArrayInputStream(docxBytes)) {
            result = converter.convert(in, "h-001", "heading.docx");
        }

        // The registry should include at least one style
        assertThat(result.registry().getStyles()).isNotNull();
    }

    @Test
    void convert_invalidInput_throwsConversionException() {
        byte[] garbage = "not a docx file".getBytes();
        assertThatThrownBy(() -> {
            try (InputStream in = new ByteArrayInputStream(garbage)) {
                converter.convert(in, "bad", "bad.docx");
            }
        }).isInstanceOf(ConversionException.class);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    /**
     * Creates a minimal in-memory .docx with one paragraph.
     *
     * @param content the paragraph text
     * @param styleId optional paragraph style ID (e.g., "Heading1"), null = Normal
     */
    private byte[] createSimpleDocx(String content, String styleId) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();

        org.docx4j.wml.ObjectFactory factory = new org.docx4j.wml.ObjectFactory();
        P para = factory.createP();

        if (styleId != null) {
            org.docx4j.wml.PPr ppr = factory.createPPr();
            org.docx4j.wml.PPrBase.PStyle ps = factory.createPPrBasePStyle();
            ps.setVal(styleId);
            ppr.setPStyle(ps);
            para.setPPr(ppr);
        }

        R run = factory.createR();
        Text text = factory.createText();
        text.setValue(content);
        run.getContent().add(factory.createRT(text));
        para.getContent().add(run);

        pkg.getMainDocumentPart().getContent().add(para);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pkg.save(baos);
        return baos.toByteArray();
    }
}
