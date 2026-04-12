package io.docpilot.processor.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.List;

/**
 * Full document structure / outline extracted from a DOCX file.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentStructure {

    String docId;
    String filename;
    Instant extractedAt;

    Integer pageCount;
    Integer wordCount;
    Integer paragraphCount;
    Integer tableCount;
    Integer imageCount;

    /** Detected document type: contract, report, academic, general, etc. */
    String docType;

    /** Flat-ish hierarchical outline (headings + major blocks). */
    List<DocumentNode> outline;

    /** Section breaks analysis. */
    List<SectionInfo> sections;

    @Value
    @Builder
    @Jacksonized
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SectionInfo {
        Integer sectionIndex;
        PageLayout layout;
        Integer startPageEstimate;
    }
}
