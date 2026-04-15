import html as html_module

from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from services.doc_mcp_client import (
    DocMcpClient,
    DocMcpResponseError,
    DocMcpUnavailableError,
)

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
            html = await client.get_html_projection(session["session_id"], fragment=True)
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
        if isinstance(exc, DocMcpUnavailableError):
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        if isinstance(exc, DocMcpResponseError):
            status_code = exc.status_code if exc.status_code in {400, 404, 409} else 502
            raise HTTPException(status_code=status_code, detail=str(exc)) from exc
        raise HTTPException(status_code=502, detail=f"Doc-mcp import failed: {exc}") from exc


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
        if isinstance(exc, DocMcpUnavailableError):
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        if isinstance(exc, DocMcpResponseError):
            status_code = exc.status_code if exc.status_code in {400, 404, 409} else 502
            raise HTTPException(status_code=status_code, detail=str(exc)) from exc
        raise HTTPException(status_code=502, detail=f"Doc-mcp export failed: {exc}") from exc

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
