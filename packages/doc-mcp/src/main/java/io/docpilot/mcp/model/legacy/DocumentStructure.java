package io.docpilot.mcp.model.legacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.List;

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

    String docType;
    List<DocumentNode> outline;
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
