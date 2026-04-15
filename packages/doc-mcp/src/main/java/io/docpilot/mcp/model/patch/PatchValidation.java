package io.docpilot.mcp.model.patch;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/** Validation result attached to a patch (after dry-run). */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PatchValidation {
    boolean structureOk;
    boolean styleOk;
    /** Non-fatal warnings to surface to the user. */
    List<String> warnings;
    /** Fatal errors — if non-empty the patch must not be applied. */
    List<String> errors;
    /** stableIds of blocks that will be affected. */
    List<String> affectedBlockIds;
    /** Estimated scope: "minor" | "moderate" | "major" */
    String scope;
}
