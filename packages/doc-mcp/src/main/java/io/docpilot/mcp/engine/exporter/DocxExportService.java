package io.docpilot.mcp.engine.exporter;

import io.docpilot.mcp.converter.HtmlToDocxConverter;
import io.docpilot.mcp.engine.projection.HtmlProjectionService;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.storage.RegistryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Exports a DocumentSession back to DOCX bytes.
 *
 * <p>Strategy: render the session's component tree to annotated HTML via
 * HtmlProjectionService, then convert HTML → DOCX via HtmlToDocxConverter,
 * optionally restoring the original style registry for faithful round-trip.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocxExportService {

    private final HtmlProjectionService projectionService;
    private final HtmlToDocxConverter htmlToDocxConverter;
    private final RegistryStore registryStore;

    /**
     * Exports the current session state to a DOCX byte array.
     *
     * @param session the document session to export
     * @return DOCX binary content
     */
    public byte[] export(DocumentSession session) {
        log.info("Exporting session {} (docId={}) to DOCX", session.getSessionId(), session.getDocId());

        // Render HTML with embedded node annotations (data-doc-node-id etc.)
        String html = projectionService.projectFragment(session);

        // Try to restore the original style registry for faithful style round-trip
        var registry = registryStore.findRegistry(session.getDocId()).orElse(null);
        if (registry == null) {
            log.info("No style registry found for docId={}, exporting with default styles", session.getDocId());
        }

        byte[] docxBytes = htmlToDocxConverter.convert(html, null, registry);
        log.info("Export complete: session={} bytes={}", session.getSessionId(), docxBytes.length);
        return docxBytes;
    }
}
