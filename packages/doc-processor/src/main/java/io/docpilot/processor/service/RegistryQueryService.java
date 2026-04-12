package io.docpilot.processor.service;

import io.docpilot.processor.exception.NotFoundException;
import io.docpilot.processor.model.DocumentStructure;
import io.docpilot.processor.model.StyleRegistry;
import io.docpilot.processor.storage.RegistryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Query service for retrieving previously extracted styles and structures.
 */
@Service
@RequiredArgsConstructor
public class RegistryQueryService {

    private final RegistryStore store;

    public StyleRegistry getRegistry(String docId) {
        return store.findRegistry(docId)
            .orElseThrow(() -> new NotFoundException(
                "No style registry found for docId=" + docId
                    + ". Convert the document first via POST /api/convert/docx-to-html."));
    }

    public DocumentStructure getStructure(String docId) {
        return store.findStructure(docId)
            .orElseThrow(() -> new NotFoundException(
                "No structure found for docId=" + docId
                    + ". Extract structure first via POST /api/structure."));
    }

    public void deleteRegistry(String docId) {
        store.deleteRegistry(docId);
    }
}
