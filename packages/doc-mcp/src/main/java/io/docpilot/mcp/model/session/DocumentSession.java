package io.docpilot.mcp.model.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.docpilot.mcp.model.document.DocumentComponent;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/**
 * Canonical document session — the source of truth for a single open DOCX.
 *
 * <p>A session holds:
 * <ul>
 *   <li>The full component tree rooted at a DOCUMENT node.</li>
 *   <li>A pointer to the current (latest applied) revision.</li>
 *   <li>Metadata about the original file.</li>
 * </ul>
 */
@Data
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentSession {

    /** UUID identifying this session (not the docId — sessions can be forked). */
    String sessionId;

    /** UUID of the original uploaded document artefact. */
    String docId;

    String filename;
    String originalFilename;

    /** The root DOCUMENT component — entire content tree hangs off this. */
    DocumentComponent root;

    /** Id of the most recently applied revision. Null for a freshly imported session. */
    String currentRevisionId;

    SessionState state;

    Instant createdAt;
    Instant lastModifiedAt;

    // ── Metadata / statistics ──
    Integer wordCount;
    Integer paragraphCount;
    Integer tableCount;
    Integer imageCount;

    /** Section count derived from the component tree. */
    Integer sectionCount;
}
