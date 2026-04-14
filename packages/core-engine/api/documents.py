import html as html_module

import httpx
from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel

from config import settings

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


# ---------------------------------------------------------------------------
# POST /api/documents/import
# ---------------------------------------------------------------------------


@router.post("/documents/import")
async def import_document(file: UploadFile = File(...)):
    """
    Accept a DOCX or PDF file, convert it to HTML via the Java doc-processor,
    and return the result to the frontend.
    """
    filename = file.filename or "document"
    content_type = file.content_type or ""

    if _is_docx(filename, content_type):
        java_endpoint = "/api/convert/docx-to-html"
    elif _is_pdf(filename, content_type):
        java_endpoint = "/api/convert/pdf-to-text"
    else:
        raise HTTPException(
            status_code=415,
            detail=f"Unsupported file type '{content_type}'. Only DOCX and PDF are supported.",
        )

    content = await file.read()

    try:
        async with httpx.AsyncClient(timeout=settings.request_timeout_seconds) as client:
            resp = await client.post(
                f"{settings.doc_processor_url}{java_endpoint}",
                files={"file": (filename, content, content_type)},
            )
    except httpx.ConnectError:
        raise HTTPException(
            status_code=503,
            detail=(
                "Doc-processor service is unavailable. "
                "Make sure the Java doc-processor is running on "
                f"{settings.doc_processor_url}."
            ),
        )

    if not resp.is_success:
        raise HTTPException(status_code=502, detail=f"Doc-processor error: {resp.text}")

    data = resp.json()

    if java_endpoint == "/api/convert/pdf-to-text":
        # PDF: wrap extracted text in basic HTML
        return {
            "docId": data.get("docId"),
            "html": _text_to_html(data.get("content", "")),
            "wordCount": data.get("wordCount", 0),
            "pageCount": 0,
            "filename": filename,
        }

    # DOCX: return html + metadata (styleRegistry stays in Java's RegistryStore)
    return {
        "docId": data.get("docId"),
        "html": data.get("html", ""),
        "wordCount": data.get("wordCount", 0),
        "pageCount": data.get("pageCount", 0),
        "filename": filename,
    }


# ---------------------------------------------------------------------------
# POST /api/documents/export
# ---------------------------------------------------------------------------


class ExportRequest(BaseModel):
    html: str
    doc_id: str | None = None
    filename: str = "document.docx"


@router.post("/documents/export")
async def export_document(request: ExportRequest):
    """
    Convert HTML back to DOCX via the Java doc-processor.
    If doc_id is provided, Java re-applies the stored style registry for that document.
    """
    payload: dict = {"html": request.html}
    if request.doc_id:
        payload["doc_id"] = request.doc_id

    try:
        async with httpx.AsyncClient(timeout=settings.request_timeout_seconds) as client:
            resp = await client.post(
                f"{settings.doc_processor_url}/api/convert/html-to-docx",
                json=payload,
            )
    except httpx.ConnectError:
        raise HTTPException(
            status_code=503,
            detail=(
                "Doc-processor service is unavailable. "
                "Make sure the Java doc-processor is running."
            ),
        )

    if not resp.is_success:
        raise HTTPException(status_code=502, detail=f"Doc-processor error: {resp.text}")

    filename = request.filename
    if not filename.lower().endswith(".docx"):
        filename = filename.rsplit(".", 1)[0] + ".docx"

    return Response(
        content=resp.content,
        media_type=(
            "application/vnd.openxmlformats-officedocument"
            ".wordprocessingml.document"
        ),
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
