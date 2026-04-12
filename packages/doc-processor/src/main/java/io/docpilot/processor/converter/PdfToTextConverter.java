package io.docpilot.processor.converter;

import io.docpilot.processor.exception.ConversionException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Extracts plain text from a PDF file using Apache PDFBox 3.x.
 *
 * <p>PDFBox is used instead of docx4j here — it is purpose-built for PDF
 * reading and handles encrypted, compressed, and image-heavy PDFs reliably.
 *
 * <p>For scanned / image-only PDFs, text extraction will be empty.
 * Integrate an OCR engine (Tesseract) as a post-processing step if needed.
 */
@Component
@Slf4j
public class PdfToTextConverter {

    public Result convert(InputStream in, String filename) {
        // PDFBox 3.x requires byte[] or File — read to memory first
        byte[] pdfBytes;
        try {
            pdfBytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ConversionException("Cannot read PDF input: " + e.getMessage(), e);
        }

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int pageCount = doc.getNumberOfPages();
            PDDocumentInformation info = doc.getDocumentInformation();

            PDFTextStripper stripper = new PDFTextStripper();
            // Preserve logical reading order rather than raw glyph order
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);

            int wordCount = text.isBlank() ? 0 : text.trim().split("\\s+").length;

            log.info("PDF→Text complete: filename={} pages={} words={}", filename, pageCount, wordCount);
            return new Result(text, pageCount, wordCount,
                info.getTitle(), info.getAuthor());

        } catch (IOException e) {
            throw new ConversionException(
                "Failed to extract text from PDF '" + filename + "': " + e.getMessage(), e);
        }
    }

    public record Result(
        String text,
        int pageCount,
        int wordCount,
        String title,
        String author
    ) {}
}
