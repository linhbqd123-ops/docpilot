package io.docpilot.mcp.model.legacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentNode {

    String id;

    /** Content type: heading | paragraph | table | list | image | section */
    String type;

    Integer level;
    String text;
    String styleId;
    Integer wordCount;
    Integer pageEstimate;
    List<DocumentNode> children;
}
