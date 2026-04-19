import json
from typing import Annotated, Any, Literal

from fastapi import APIRouter, HTTPException
from fastapi.requests import Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from agents.turn_orchestrator import stream_turn
from debug_logging import get_trace_id, log_core_event
from providers import ProviderName, get_provider
from services.doc_mcp_client import DocMcpClient, MappedDocMcpError, map_doc_mcp_error
from services.provider_settings_store import ProviderSettingsStore
from config import settings

router = APIRouter()

# Initialize provider settings store
_provider_settings_store = ProviderSettingsStore(settings.chat_database_path)


class TextRange(BaseModel):
    start: int | None = None
    end: int | None = None


class SelectionContext(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    block_id: str | None = Field(default=None, alias="blockId")
    text_range: TextRange | None = Field(default=None, alias="textRange")

    def to_payload(self) -> dict[str, Any]:
        return {
            "blockId": self.block_id,
            "textRange": self.text_range.model_dump() if self.text_range else None,
        }


class WorkspaceContext(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    document_ids: list[str] = Field(default_factory=list, alias="documentIds")
    active_pane: str | None = Field(default=None, alias="activePane")
    visible_block_ids: list[str] = Field(default_factory=list, alias="visibleBlockIds")

    def to_payload(self) -> dict[str, Any]:
        return {
            "documentIds": self.document_ids,
            "activePane": self.active_pane,
            "visibleBlockIds": self.visible_block_ids,
        }


class AgentExecutionConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    max_input_tokens: int | None = Field(default=None, alias="maxInputTokens")
    session_context_budget_tokens: int | None = Field(default=None, alias="sessionContextBudgetTokens")
    tool_result_budget_tokens: int | None = Field(default=None, alias="toolResultBudgetTokens")
    max_tool_batch_size: int | None = Field(default=None, alias="maxToolBatchSize")
    max_parallel_tools: int | None = Field(default=None, alias="maxParallelTools")
    max_heavy_tools_per_turn: int | None = Field(default=None, alias="maxHeavyToolsPerTurn")
    auto_compact_session: bool | None = Field(default=None, alias="autoCompactSession")
    # If true, this request is explicitly asking the server to perform compaction
    # for this turn even if server config disables auto-compaction.
    force_compact: bool | None = Field(default=None, alias="forceCompact")
    auto_compact_threshold: float | None = Field(default=None, alias="autoCompactThreshold")

    def to_payload(self) -> dict[str, Any]:
        return self.model_dump(by_alias=True, exclude_none=True)


class TurnHistoryMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str


class AgentTurnRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    provider: ProviderName = "ollama"
    model: str | None = None
    chat_id: str = Field(alias="chatId")
    document_session_id: str = Field(alias="documentSessionId")
    mode: Literal["ask", "agent"]
    base_revision_id: str | None = Field(default=None, alias="baseRevisionId")
    prompt: Annotated[str, Field(min_length=1)]
    selection: SelectionContext | None = None
    workspace_context: WorkspaceContext | None = Field(default=None, alias="workspaceContext")
    agent_config: AgentExecutionConfig | None = Field(default=None, alias="agentConfig")
    history: list[TurnHistoryMessage] = Field(default_factory=list)


class CompareRevisionsRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    document_session_id: str = Field(alias="documentSessionId")
    base_revision_id: str = Field(alias="baseRevisionId")
    target_revision_id: str = Field(alias="targetRevisionId")


def _sse(event_name: str, payload: Any) -> str:
    return f"event: {event_name}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _resolve_provider(name: ProviderName, model_override: str | None):
    """
    Resolve a provider with optional model override.
    
    Priority:
    1. model_override parameter (from frontend request)
    2. Model override from DB (per-provider setting)
    3. Default model from provider config
    """
    try:
        # If no override from frontend, check DB for saved per-provider override
        effective_model = model_override
        if not effective_model:
            db_model = _provider_settings_store.get_model_override(name)
            if db_model:
                effective_model = db_model
        
        return get_provider(name, model_override=effective_model)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


def _mapped_doc_mcp_error(exc: Exception) -> MappedDocMcpError:
    return map_doc_mcp_error(
        exc,
        internal_message="The assistant could not complete the document request right now. Please try again.",
    )


def _map_doc_mcp_error(exc: Exception, *, context: dict[str, Any] | None = None) -> HTTPException:
    """Map a doc-mcp error to HTTPException with structured logging."""
    mapped = _mapped_doc_mcp_error(exc)
    error_payload = {
        "error": str(exc),
        "errorCode": mapped.code or "unknown",
        "mappedStatus": mapped.status_code,
        "errorItems": list(mapped.items) if mapped.items else None,
        "exceptionType": type(exc).__name__,
        "exception": exc,
    }
    if context:
        error_payload["context"] = context
    log_core_event("agent.error.doc_mcp", **error_payload)
    return HTTPException(status_code=mapped.status_code, detail=mapped.message)


def _doc_mcp_client() -> DocMcpClient:
    return DocMcpClient()


async def _run_turn(request: AgentTurnRequest):
    log_core_event(
        "agent.api.request",
        traceId=get_trace_id() or None,
        provider=request.provider,
        model=request.model,
        chatId=request.chat_id,
        documentSessionId=request.document_session_id,
        mode=request.mode,
        baseRevisionId=request.base_revision_id,
        prompt=request.prompt,
        selection=request.selection.model_dump(by_alias=True) if request.selection else None,
        workspaceContext=request.workspace_context.model_dump(by_alias=True) if request.workspace_context else None,
        agentConfig=request.agent_config.model_dump(by_alias=True) if request.agent_config else None,
        history=[message.model_dump() for message in request.history],
    )
    provider = _resolve_provider(request.provider, request.model)
    history = [message.model_dump() for message in request.history]
    selection = request.selection.to_payload() if request.selection else None
    workspace_context = request.workspace_context.to_payload() if request.workspace_context else None
    agent_config = request.agent_config.to_payload() if request.agent_config else None

    async for event_type, data in stream_turn(
        provider,
        prompt=request.prompt,
        mode=request.mode,
        document_session_id=request.document_session_id,
        base_revision_id=request.base_revision_id,
        selection=selection,
        workspace_context=workspace_context,
        execution_config=agent_config,
        history=history,
    ):
        yield event_type, data


async def _session_payload(session_id: str) -> dict[str, Any]:
    client = _doc_mcp_client()
    summary = await client.get_session_summary(session_id)
    revisions = await client.list_session_revisions(session_id)
    return {
        "session": summary,
        "revisions": revisions,
    }


async def _session_render_payload(session_id: str) -> dict[str, Any]:
    client = _doc_mcp_client()
    source_html = await client.get_source_html(session_id)
    return {
        "documentSessionId": session_id,
        "html": source_html,
        "sourceHtml": source_html,
    }


@router.post("/agent/turn/stream")
async def agent_turn_stream(request: AgentTurnRequest):
    async def event_generator():
        log_core_event(
            "agent.api.stream.open",
            traceId=get_trace_id() or None,
            chatId=request.chat_id,
            documentSessionId=request.document_session_id,
        )
        try:
            async for event_type, data in _run_turn(request):
                if event_type != "assistant_delta":
                    log_core_event(
                        "agent.api.stream.event",
                        traceId=get_trace_id() or None,
                        eventType=event_type,
                        payload=data,
                    )
                yield _sse(event_type, data)
        except Exception as exc:  # noqa: BLE001
            mapped = _mapped_doc_mcp_error(exc)
            log_core_event(
                "agent.api.stream.error",
                traceId=get_trace_id() or None,
                statusCode=mapped.status_code,
                detail=mapped.message,
                internalDetail=str(exc),
            )
            notice_payload: dict[str, Any] = {
                "message": mapped.message,
                "statusCode": mapped.status_code,
            }
            if mapped.code:
                notice_payload["code"] = mapped.code
            if mapped.items:
                notice_payload["items"] = list(mapped.items)
            yield _sse("notice", notice_payload)
            yield _sse(
                "done",
                {
                    "status": "failed",
                    "message": mapped.message,
                    "documentSessionId": request.document_session_id,
                },
            )

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


@router.post("/agent/turn")
async def agent_turn(request: AgentTurnRequest):
    assistant_chunks: list[str] = []
    notices: list[dict[str, Any]] = []
    tool_activity: list[dict[str, Any]] = []
    final_payload: dict[str, Any] | None = None

    try:
        async for event_type, data in _run_turn(request):
            if event_type == "assistant_delta":
                assistant_chunks.append(str(data))
            elif event_type in {"tool_started", "tool_finished"}:
                tool_activity.append({"event": event_type, **data})
            elif event_type == "notice":
                notices.append(data)
            elif event_type == "done":
                final_payload = data
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc) from exc

    payload = final_payload or {
        "message": "".join(assistant_chunks),
        "mode": request.mode,
        "documentSessionId": request.document_session_id,
        "baseRevisionId": request.base_revision_id,
        "revisionId": None,
        "status": "completed",
    }
    payload["message"] = payload.get("message") or "".join(assistant_chunks)
    payload["chatId"] = request.chat_id
    payload["toolActivity"] = tool_activity
    if notices:
        payload["notices"] = notices
    log_core_event(
        "agent.api.response",
        traceId=get_trace_id() or None,
        payload=payload,
    )
    return payload


@router.get("/agent/sessions/{session_id}")
async def get_agent_session(session_id: str):
    try:
        return await _session_payload(session_id)
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc) from exc


@router.get("/agent/sessions/{session_id}/projection")
async def get_agent_session_projection(session_id: str, fragment: bool = True):
    try:
        if fragment:
            return await _session_render_payload(session_id)
        client = _doc_mcp_client()
        html = await client.get_source_html(session_id)
        return {"documentSessionId": session_id, "html": html, "sourceHtml": html}
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc) from exc


@router.get("/agent/sessions/{session_id}/revisions")
async def list_agent_session_revisions(session_id: str, status: str | None = None):
    client = _doc_mcp_client()
    try:
        revisions = await client.list_session_revisions(session_id, status=status)
        return {"documentSessionId": session_id, "revisions": revisions}
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc) from exc


@router.put("/agent/sessions/{session_id}/html")
async def sync_session_html(session_id: str, request: Request):
    """
    Accept the frontend's current source HTML and forward it to doc-mcp.
    This keeps the stored HTML in sync before each agent turn so that MCP
    tools always operate on the most up-to-date version of the document.
    """
    client = _doc_mcp_client()
    try:
        html = (await request.body()).decode("utf-8")
        await client.update_session_html(session_id, html)
        return {"ok": True, "documentSessionId": session_id}
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc) from exc


@router.get("/agent/revisions/{revision_id}")
async def get_agent_revision(revision_id: str):
    client = _doc_mcp_client()
    try:
        revision = await client.get_revision(revision_id)
        payload: dict[str, Any] = {"revision": revision}
        session_id = revision.get("sessionId") or revision.get("session_id")
        status = revision.get("status")
        if session_id and status == "PENDING":
            payload["review"] = await client.review_pending_revision(session_id, revision_id)
        return payload
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc) from exc

@router.get("/agent/revisions/{revision_id}/preview")
async def preview_agent_revision(revision_id: str):
    client = _doc_mcp_client()
    try:
        preview = await client.preview_revision(revision_id)
        session_id = preview.get("sessionId") or preview.get("session_id")
        return {
            "revisionId": revision_id,
            "documentSessionId": session_id,
            **preview,
        }
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc, context={"revisionId": revision_id, "operation": "preview"}) from exc


@router.post("/agent/revisions/{revision_id}/apply")
async def apply_agent_revision(revision_id: str):
    client = _doc_mcp_client()
    try:
        log_core_event(
            "agent.revision.apply.start",
            revisionId=revision_id,
        )
        revision = await client.get_revision(revision_id)
        session_id = revision.get("sessionId") or revision.get("session_id")
        if not session_id:
            raise HTTPException(status_code=500, detail="Revision does not include a session id.")
        result = await client.apply_revision(revision_id)
        session_payload = await _session_payload(session_id)
        log_core_event(
            "agent.revision.apply.success",
            revisionId=revision_id,
            sessionId=session_id,
        )
        return {
            "result": result,
            **session_payload,
            **(await _session_render_payload(session_id)),
        }
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc, context={"revisionId": revision_id, "operation": "apply"}) from exc


class PartialApplyRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    accepted_indices: list[int] = Field(default_factory=list, alias="acceptedIndices")


@router.post("/agent/revisions/{revision_id}/apply-partial")
async def apply_partial_agent_revision(revision_id: str, body: PartialApplyRequest):
    client = _doc_mcp_client()
    try:
        log_core_event("agent.revision.apply_partial.start", revisionId=revision_id)
        revision = await client.get_revision(revision_id)
        session_id = revision.get("sessionId") or revision.get("session_id")
        if not session_id:
            raise HTTPException(status_code=500, detail="Revision does not include a session id.")
        result = await client.apply_partial_revision(revision_id, body.accepted_indices)
        session_payload = await _session_payload(session_id)
        log_core_event("agent.revision.apply_partial.success", revisionId=revision_id, sessionId=session_id)
        return {
            "result": result,
            **session_payload,
            **(await _session_render_payload(session_id)),
        }
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc, context={"revisionId": revision_id, "operation": "apply-partial"}) from exc


@router.post("/agent/revisions/{revision_id}/reject")
async def reject_agent_revision(revision_id: str):
    client = _doc_mcp_client()
    try:
        log_core_event("agent.revision.reject.start", revisionId=revision_id)
        revision = await client.get_revision(revision_id)
        session_id = revision.get("sessionId") or revision.get("session_id")
        result = await client.reject_revision(revision_id)
        log_core_event("agent.revision.reject.success", revisionId=revision_id, sessionId=session_id)
        payload = {"result": result}
        if session_id:
            payload.update(await _session_payload(session_id))
            payload.update(await _session_render_payload(session_id))
        return payload
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc, context={"revisionId": revision_id, "operation": "reject"}) from exc


@router.post("/agent/revisions/{revision_id}/rollback")
async def rollback_agent_revision(revision_id: str):
    client = _doc_mcp_client()
    try:
        log_core_event("agent.revision.rollback.start", revisionId=revision_id)
        revision = await client.get_revision(revision_id)
        session_id = revision.get("sessionId") or revision.get("session_id")
        if not session_id:
            raise HTTPException(status_code=500, detail="Revision does not include a session id.")
        result = await client.rollback_revision(revision_id)
        session_payload = await _session_payload(session_id)
        log_core_event("agent.revision.rollback.success", revisionId=revision_id, sessionId=session_id)
        return {
            "result": result,
            **session_payload,
            **(await _session_render_payload(session_id)),
        }
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc, context={"revisionId": revision_id, "operation": "rollback"}) from exc


@router.post("/agent/revisions/{revision_id}/restore")
async def restore_agent_revision(revision_id: str):
    client = _doc_mcp_client()
    try:
        log_core_event("agent.revision.restore.start", revisionId=revision_id)
        revision = await client.get_revision(revision_id)
        session_id = revision.get("sessionId") or revision.get("session_id")
        if not session_id:
            raise HTTPException(status_code=500, detail="Revision does not include a session id.")
        result = await client.restore_revision(revision_id)
        session_payload = await _session_payload(session_id)
        log_core_event("agent.revision.restore.success", revisionId=revision_id, sessionId=session_id)
        return {
            "result": result,
            **session_payload,
            **(await _session_render_payload(session_id)),
        }
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc, context={"revisionId": revision_id, "operation": "restore"}) from exc


@router.post("/agent/revisions/compare")
async def compare_agent_revisions(request: CompareRevisionsRequest):
    client = _doc_mcp_client()
    try:
        diff = await client.compare_revisions(
            request.document_session_id,
            request.base_revision_id,
            request.target_revision_id,
        )
        return {
            "documentSessionId": request.document_session_id,
            "baseRevisionId": request.base_revision_id,
            "targetRevisionId": request.target_revision_id,
            "diff": diff,
        }
    except Exception as exc:  # noqa: BLE001
        raise _map_doc_mcp_error(exc) from exc