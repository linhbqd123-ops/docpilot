import json
from typing import Annotated, Literal

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from agents.edit_agent import stream_edit
from providers import ProviderName, get_provider, list_providers

router = APIRouter()


# ---------------------------------------------------------------------------
# Request / response models
# ---------------------------------------------------------------------------


class DocumentContext(BaseModel):
    id: str
    name: str
    kind: str
    html: str = ""
    outline: list = []
    wordCount: int = 0


class HistoryMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class ChatRequest(BaseModel):
    provider: ProviderName = "ollama"
    # Optional model override — lets the frontend specify the exact model
    # (e.g. "llama3.2", "gpt-4o-mini", "anthropic/claude-3-haiku" on OpenRouter)
    model: str | None = None
    prompt: Annotated[str, Field(min_length=1)]
    document: DocumentContext | None = None
    history: list[HistoryMessage] = []
    streaming: bool = True


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _sse(payload: dict) -> str:
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/providers")
async def get_providers():
    """Return the list of configured providers (for the frontend dropdown)."""
    return {"providers": list_providers()}


@router.post("/chat")
async def chat(request: ChatRequest):
    try:
        provider = get_provider(request.provider, model_override=request.model)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    document_html = request.document.html if request.document else ""
    history = [{"role": m.role, "content": m.content} for m in request.history]

    if request.streaming:
        # ── Streaming SSE response ──────────────────────────────────────────
        async def event_generator():
            try:
                async for event_type, data in stream_edit(
                    provider=provider,
                    prompt=request.prompt,
                    document_html=document_html,
                    history=history,
                ):
                    if event_type == "delta":
                        yield _sse({"delta": data})
                    elif event_type == "done":
                        if data:
                            yield _sse({"documentHtml": data})
                        yield "data: [DONE]\n\n"
                    elif event_type == "notice":
                        yield _sse({"notices": [data]})
            except Exception as exc:  # noqa: BLE001
                yield _sse({"notices": [f"Unexpected error: {exc}"]})
                yield "data: [DONE]\n\n"

        return StreamingResponse(
            event_generator(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
                "Connection": "keep-alive",
            },
        )

    else:
        # ── Non-streaming JSON response ─────────────────────────────────────
        full_text = ""
        doc_html = ""
        notices: list[str] = []

        try:
            async for event_type, data in stream_edit(
                provider=provider,
                prompt=request.prompt,
                document_html=document_html,
                history=history,
            ):
                if event_type == "delta":
                    full_text += data
                elif event_type == "done":
                    doc_html = data
                elif event_type == "notice":
                    notices.append(data)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=500, detail=str(exc)) from exc

        payload: dict = {"message": full_text}
        if doc_html:
            payload["documentHtml"] = doc_html
        if notices:
            payload["notices"] = notices
        return payload

