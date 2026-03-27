"""DOC-MCP REST API routes.

Exposes document operations as RESTful endpoints.
Designed with a clean interface that can later be adapted to a true MCP server.
"""

import logging

from fastapi import APIRouter, HTTPException

from app.models.document import (
    ApplyChangesRequest,
    DocumentResponse,
    DocumentStructure,
    ExtractRequest,
    InsertContentRequest,
)
from app.services.document_service import document_service

logger = logging.getLogger(__name__)


def _short_text(text: str | None, limit: int = 1000) -> str:
    if not text:
        return ""
    normalized = " ".join(text.split())
    if len(normalized) <= limit:
        return normalized
    return normalized[:limit] + "...(truncated)"

router = APIRouter(prefix="/api/v1", tags=["document"])


@router.post("/extract-structure", response_model=DocumentStructure)
async def extract_structure(request: ExtractRequest):
    """Extract the structured representation of a Word document."""
    try:
        logger.debug(
            "extract_structure request include_formatting=%s has_document=%s",
            request.include_formatting,
            bool(request.document_base64),
        )
        structure = document_service.extract_structure(
            document_base64=request.document_base64,
            include_formatting=request.include_formatting,
        )
        logger.info("Extracted %d blocks from document", len(structure.blocks))
        return structure
    except Exception as e:
        logger.exception("Failed to extract structure")
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/apply-changes", response_model=DocumentResponse)
async def apply_changes(request: ApplyChangesRequest):
    """Apply block-level changes to a document."""
    try:
        logger.debug(
            "apply_changes request changes=%d first_change=%s",
            len(request.changes),
            _short_text(str(request.changes[0].model_dump() if request.changes else {})),
        )
        result = document_service.apply_changes(
            changes=request.changes,
            document_base64=request.document_base64,
        )
        logger.info("Applied %d changes", len(request.changes))
        return result
    except Exception as e:
        logger.exception("Failed to apply changes")
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/clear-document", response_model=DocumentResponse)
async def clear_document(request: ExtractRequest):
    """Clear all content from a document."""
    try:
        logger.debug("clear_document request has_document=%s", bool(request.document_base64))
        result = document_service.clear_document(
            document_base64=request.document_base64,
        )
        logger.info("Document cleared")
        return result
    except Exception as e:
        logger.exception("Failed to clear document")
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/insert-structured-content", response_model=DocumentResponse)
async def insert_structured_content(request: InsertContentRequest):
    """Insert structured content blocks into a document."""
    try:
        logger.debug(
            "insert_structured_content request blocks=%d position=%s first_block=%s",
            len(request.blocks),
            request.position,
            _short_text(str(request.blocks[0].model_dump() if request.blocks else {})),
        )
        result = document_service.insert_structured_content(
            blocks=request.blocks,
            position=request.position,
            document_base64=request.document_base64,
        )
        logger.info("Inserted %d blocks", len(request.blocks))
        return result
    except Exception as e:
        logger.exception("Failed to insert content")
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/health")
async def health_check():
    """Health check endpoint."""
    return {"status": "healthy", "service": "doc-mcp"}
