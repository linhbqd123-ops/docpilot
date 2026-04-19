import html as html_module
from functools import lru_cache

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from api.chats import get_chat_store
from config import settings
from services.chat_store import SQLiteChatStore
from services.doc_mcp_client import (
    DocMcpClient,
    map_doc_mcp_error,
)
from services.document_store import SQLiteDocumentStore

router = APIRouter()

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_DOCX_MIMES = {
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
    "application/octet-stream",
}
_PDF_MIMES = {"application/pdf", "application/octet-stream"}


def _is_docx(filename: str | None, content_type: str | None) -> bool:
    name = (filename or "").lower()
    mime = content_type or ""
    return name.endswith((".docx", ".doc")) or mime in _DOCX_MIMES


def _is_pdf(filename: str | None, content_type: str | None) -> bool:
    name = (filename or "").lower()
    mime = content_type or ""
    return name.endswith(".pdf") or mime in _PDF_MIMES


def _text_to_html(text: str) -> str:
    """Convert plain text (from PDF extraction) to minimal HTML."""
    paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
    escaped = (
        html_module.escape(p).replace("\n", "<br />") for p in paragraphs
    )
    return "".join(f"<p>{e}</p>" for e in escaped)


def _doc_mcp_client() -> DocMcpClient:
    return DocMcpClient()


@lru_cache(maxsize=1)
def get_document_store() -> SQLiteDocumentStore:
    return SQLiteDocumentStore(settings.chat_database_path)


class LibraryDocumentRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str
    name: str
    kind: str
    mimeType: str = ""
    size: int = 0
    status: str = "ready"
    html: str = ""
    sourceHtml: str | None = None
    outline: list[dict] = Field(default_factory=list)
    wordCount: int = 0
    createdAt: int
    updatedAt: int
    backendDocId: str | None = None
    documentSessionId: str | None = None
    baseRevisionId: str | None = None
    currentRevisionId: str | None = None
    pendingRevisionId: str | None = None
    revisionStatus: str | None = None
    sessionState: str | None = None
    reviewPayload: dict | None = None
    revisions: list[dict] = Field(default_factory=list)
    error: str | None = None


@router.get("/documents")
async def list_library_documents(store: SQLiteDocumentStore = Depends(get_document_store)):
    return {"documents": store.list_documents()}


@router.get("/documents/{document_id}")
async def get_library_document(
    document_id: str,
    store: SQLiteDocumentStore = Depends(get_document_store),
):
    document = store.get_document(document_id)
    if document is None:
        raise HTTPException(status_code=404, detail="Document not found")
    return {"document": document}


@router.post("/documents")
async def create_or_replace_library_document(
    body: LibraryDocumentRequest,
    store: SQLiteDocumentStore = Depends(get_document_store),
):
    document = store.create_or_replace_document(body.model_dump())
    return {"document": document}


@router.delete("/documents/{document_id}")
async def delete_library_document(
    document_id: str,
    store: SQLiteDocumentStore = Depends(get_document_store),
    chat_store: SQLiteChatStore = Depends(get_chat_store),
):
    document = store.get_document(document_id)
    if document is None:
        raise HTTPException(status_code=404, detail="Document not found")
    document_session_id: str | None = document.get("documentSessionId")
    store.delete_document(document_id)
    chat_store.delete_chats_for_document(document_id)
    if document_session_id:
        try:
            await _doc_mcp_client().delete_session(document_session_id)
        except Exception:  # noqa: BLE001
            pass  # best-effort; session may already be gone
    return {"ok": True}


@router.delete("/documents")
async def delete_all_library_documents(store: SQLiteDocumentStore = Depends(get_document_store)):
    store.delete_all_documents()
    return {"ok": True}


# ---------------------------------------------------------------------------
# POST /api/documents/import
# ---------------------------------------------------------------------------


@router.post("/documents/import")
async def import_document(file: UploadFile = File(...)):
    """
    Accept a DOCX or PDF file and return an editable HTML representation.
    """
    filename = file.filename or "document"
    content_type = file.content_type or ""

    if _is_docx(filename, content_type):
        import_kind = "docx"
    elif _is_pdf(filename, content_type):
        import_kind = "pdf"
    else:
        raise HTTPException(
            status_code=415,
            detail=f"Unsupported file type '{content_type}'. Only DOCX and PDF are supported.",
        )

    content = await file.read()
    client = _doc_mcp_client()

    try:
        if import_kind == "docx":
            session = await client.import_docx_session(filename, content, content_type)
            html = session.get("source_html") or await client.get_source_html(session["session_id"])
            return {
                "docId": session.get("doc_id"),
                "documentSessionId": session.get("session_id"),
                "baseRevisionId": None,
                "html": html,
                "wordCount": session.get("word_count", 0),
                "pageCount": 0,
                "filename": filename,
            }
        data = await client.import_pdf_to_html(filename, content, content_type)
        return {
            "docId": None,
            "documentSessionId": None,
            "baseRevisionId": None,
            "html": data.get("html") or _text_to_html(data.get("content", "")),
            "wordCount": data.get("word_count", data.get("wordCount", 0)),
            "pageCount": data.get("page_count", data.get("pageCount", 0)),
            "filename": filename,
        }
    except Exception as exc:  # noqa: BLE001
        mapped = map_doc_mcp_error(
            exc,
            internal_message="Could not import the document right now. Please try again.",
        )
        raise HTTPException(status_code=mapped.status_code, detail=mapped.message) from exc


# ---------------------------------------------------------------------------
# POST /api/documents/export
# ---------------------------------------------------------------------------


class ExportRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    document_session_id: str | None = Field(default=None, alias="documentSessionId")
    filename: str = "document.docx"


@router.post("/documents/export")
async def export_document(request: ExportRequest):
    """
    Export the current document to DOCX.
    """
    if not request.document_session_id:
        raise HTTPException(status_code=400, detail="documentSessionId is required for export.")

    client = _doc_mcp_client()
    try:
        docx_bytes = await client.export_session_docx(request.document_session_id)
    except Exception as exc:  # noqa: BLE001
        mapped = map_doc_mcp_error(
            exc,
            internal_message="Could not export the document right now. Please try again.",
        )
        raise HTTPException(status_code=mapped.status_code, detail=mapped.message) from exc

    filename = request.filename
    if not filename.lower().endswith(".docx"):
        filename = filename.rsplit(".", 1)[0] + ".docx"

    return Response(
        content=docx_bytes,
        media_type=(
            "application/vnd.openxmlformats-officedocument"
            ".wordprocessingml.document"
        ),
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
