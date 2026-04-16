package io.docpilot.mcp.engine.exporter;

import io.docpilot.mcp.converter.HtmlToDocxConverter;
import io.docpilot.mcp.engine.fidelity.FidelityHtmlService;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.store.DocumentSessionStore;
import io.docpilot.mcp.storage.RegistryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Exports a DocumentSession back to DOCX bytes.
 *
 * <p>Strategy: convert the current fidelity-preserving source HTML back to DOCX,
 * restoring the original style registry when available for faithful round-trip.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocxExportService {

    private final HtmlToDocxConverter htmlToDocxConverter;
    private final RegistryStore registryStore;
    private final DocumentSessionStore sessionStore;

    /**
     * Exports the current session state to a DOCX byte array.
     *
     * @param session the document session to export
     * @return DOCX binary content
     */
    public byte[] export(DocumentSession session) {
        log.info("Exporting session {} (docId={}) to DOCX", session.getSessionId(), session.getDocId());

        if (session.getCurrentRevisionId() == null) {
            var originalSnapshot = sessionStore.findDocxSnapshot(session.getSessionId());
            if (originalSnapshot.isPresent()) {
                log.info("No applied revisions for session {}, returning original DOCX snapshot", session.getSessionId());
                return originalSnapshot.get();
            }
        }

        String currentSourceHtml = sessionStore.findTextAsset(session.getSessionId(), FidelityHtmlService.CURRENT_SOURCE_ASSET_NAME)
            .orElseThrow(() -> new IllegalStateException(
                "Fidelity source HTML is missing for session " + session.getSessionId() + ". Export cannot continue."
            ));

        var registry = registryStore.findRegistry(session.getDocId()).orElse(null);
        if (registry == null) {
            log.info("No style registry found for docId={}, exporting with default styles", session.getDocId());
        }

        byte[] docxBytes = htmlToDocxConverter.convert(currentSourceHtml, null, registry);
        log.info("Export complete from fidelity HTML: session={} bytes={}", session.getSessionId(), docxBytes.length);
        return docxBytes;
    }
}
