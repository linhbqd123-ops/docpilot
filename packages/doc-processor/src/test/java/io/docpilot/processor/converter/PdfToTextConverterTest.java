package io.docpilot.processor.converter;

import io.docpilot.processor.exception.ConversionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link PdfToTextConverter}.
 */
class PdfToTextConverterTest {

    private PdfToTextConverter converter;

    @BeforeEach
    void setUp() {
        converter = new PdfToTextConverter();
    }

    @Test
    void convert_invalidInput_throwsConversionException() {
        byte[] garbage = "not a pdf".getBytes();
        assertThatThrownBy(() ->
            converter.convert(new ByteArrayInputStream(garbage), "bad.pdf")
        ).isInstanceOf(ConversionException.class);
    }
}
