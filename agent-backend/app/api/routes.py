"""Agent Backend API routes."""

from __future__ import annotations

import logging
import uuid
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from llm_core.core.config import load_llm_config
from llm_core.core.router import LLMRouter

router = APIRouter(prefix="/api/v1", tags=["agent"])

logger = logging.getLogger(__name__)
frontend_logger = logging.getLogger("frontend_trace")


def _short_text(text: Optional[str], limit: int = 1200) -> str:
    if not text:
        return ""
    normalized = " ".join(text.split())
    if len(normalized) <= limit:
        return normalized
    return normalized[:limit] + "...(truncated)"


async def _maybe_reload_llm_config(req: Request) -> None:
    """Reload LLM config if config file changed on disk."""
    agent = getattr(req.app.state, "agent", None)
    if agent is None:
        return

    config_path = getattr(req.app.state, "llm_config_path", None)
    if not config_path:
        return

    path = Path(config_path)
    if not path.exists():
        return

    current_mtime = path.stat().st_mtime
    cached_mtime = getattr(req.app.state, "llm_config_mtime", None)
    if cached_mtime is not None and current_mtime <= cached_mtime:
        return

    logger.info("Detected change in llm_config.json, reloading LLM router")
    llm_config = load_llm_config(config_path)
    new_router = LLMRouter(llm_config)

    old_router = agent.llm_router
    agent.llm_router = new_router
    req.app.state.llm_config_mtime = current_mtime
    await old_router.close()

    logger.info(
        "LLM config reloaded with providers: %s",
        [f"{p.name}:{p.model}" for p in llm_config.providers],
    )


class AgentRequest(BaseModel):
    """Request to the DocPilot agent."""
    message: str
    document_base64: Optional[str] = None
    mode: Optional[str] = None  # auto | preserve | rebuild
    action: Optional[str] = None  # rewrite | improve | tailor_cv | generate
    provider_name: Optional[str] = None  # LLM provider to use


class AgentResponse(BaseModel):
    """Response from the DocPilot agent."""
    success: bool
    message: str
    document_base64: Optional[str] = None
    changes_summary: Optional[str] = None
    structure: Optional[dict] = None


@router.post("/agent/run", response_model=AgentResponse)
async def run_agent(request: AgentRequest, req: Request):
    """Execute the DocPilot agent with the given request."""
    trace_id = str(uuid.uuid4())[:8]
    await _maybe_reload_llm_config(req)

    agent = getattr(req.app.state, "agent", None)
    if agent is None:
        raise HTTPException(status_code=503, detail="Agent not initialized")

    logger.info(
        "[FLOW:%s] run_agent start action=%s mode=%s provider=%s has_document=%s message=%s",
        trace_id,
        request.action,
        request.mode,
        request.provider_name,
        bool(request.document_base64),
        _short_text(request.message, 400),
    )
    result = await agent.run(
        user_message=request.message,
        document_base64=request.document_base64,
        mode=request.mode,
        action=request.action,
        provider_name=request.provider_name,
    )

    logger.info(
        "[FLOW:%s] run_agent done success=%s message=%s has_updated_doc=%s",
        trace_id,
        result.success,
        _short_text(result.message, 400),
        bool(result.document_base64),
    )

    return AgentResponse(**result.to_dict())


class FrontendLogRequest(BaseModel):
    level: str = "INFO"
    event: str
    message: str
    payload: Optional[dict] = None


@router.post("/agent/logs/frontend")
async def ingest_frontend_log(request: FrontendLogRequest):
    """Ingest frontend trace logs so they are persisted to frontend.log."""
    payload_preview = request.payload or {}
    frontend_logger.info(
        "event=%s level=%s message=%s payload=%s",
        request.event,
        request.level,
        _short_text(request.message, 400),
        _short_text(str(payload_preview), 1200),
    )
    return {"status": "ok"}


class ProviderInfo(BaseModel):
    name: str
    model: str
    tier: str


class StatusResponse(BaseModel):
    status: str
    providers: list[ProviderInfo] = Field(default_factory=list)


@router.get("/agent/status", response_model=StatusResponse)
async def agent_status(req: Request):
    """Get the agent's status and available providers."""
    await _maybe_reload_llm_config(req)

    agent = getattr(req.app.state, "agent", None)
    if agent is None:
        return StatusResponse(status="not_initialized")

    providers = [
        ProviderInfo(
            name=p.name,
            model=p.model,
            tier=p.tier.value,
        )
        for p in agent.llm_router.config.providers
    ]
    return StatusResponse(status="ready", providers=providers)


@router.get("/health")
async def health_check():
    return {"status": "healthy", "service": "agent-backend"}


# ========== Persistence Layer Endpoints ==========


class DocumentUploadRequest(BaseModel):
    """Request to upload and store a document."""
    filename: str
    content_base64: str
    metadata: Optional[dict] = None


class DocumentResponse(BaseModel):
    """Response containing document info."""
    id: int
    filename: str
    metadata: Optional[dict] = None
    uploaded_at: str


@router.post("/documents/upload", response_model=DocumentResponse)
async def upload_document(request: DocumentUploadRequest, req: Request):
    """Upload and store a document."""
    from app.persistence.database import db_service
    from app.persistence.models import Document

    try:
        doc = Document(
            filename=request.filename,
            content_base64=request.content_base64,
            metadata=request.metadata or {},
        )
        created = await db_service.create_document(doc)
        logger.info(f"Document uploaded: {request.filename} (ID: {created.id})")

        # Publish event
        event_bus = getattr(req.app.state, "event_bus", None)
        if event_bus:
            from app.persistence.event_bus import EventType
            await event_bus.publish(
                await event_bus._create_event(
                    EventType.DOCUMENT_UPLOADED, created.id, None, {"filename": request.filename}
                )
            )

        return DocumentResponse(
            id=created.id,
            filename=created.filename,
            metadata=created.metadata,
            uploaded_at=created.uploaded_at.isoformat(),
        )
    except Exception as e:
        logger.exception("Failed to upload document")
        raise HTTPException(status_code=500, detail=str(e))


class ConversationResponse(BaseModel):
    """Response containing conversation info."""
    id: int
    doc_id: int
    user_message: str
    agent_response: str
    action: Optional[str] = None
    mode: Optional[str] = None
    provider_used: Optional[str] = None
    success: bool
    created_at: str


@router.get("/documents/{doc_id}/history", response_model=list[ConversationResponse])
async def get_conversation_history(doc_id: int, req: Request):
    """Get conversation history for a document."""
    from app.persistence.database import db_service

    try:
        conversations = await db_service.get_conversations_for_doc(doc_id)
        return [
            ConversationResponse(
                id=c.id,
                doc_id=c.doc_id,
                user_message=c.user_message,
                agent_response=c.agent_response,
                action=c.action,
                mode=c.mode,
                provider_used=c.provider_used,
                success=c.success,
                created_at=c.created_at.isoformat(),
            )
            for c in conversations
        ]
    except Exception as e:
        logger.exception("Failed to get conversation history")
        raise HTTPException(status_code=500, detail=str(e))


class EventResponse(BaseModel):
    """Response containing event info."""
    id: int
    event_type: str
    doc_id: Optional[int] = None
    conversation_id: Optional[int] = None
    payload: dict
    processed: bool
    retry_count: int
    created_at: str


@router.get("/events", response_model=list[EventResponse])
async def get_events(processed: Optional[bool] = None, req: Request = None):
    """Get events, optionally filtered by processed status."""
    from app.persistence.database import db_service

    try:
        events = await db_service.get_pending_events()

        # Filter by processed status if provided
        if processed is not None:
            events = [e for e in events if e.processed == processed]

        return [
            EventResponse(
                id=e.id,
                event_type=e.event_type,
                doc_id=e.doc_id,
                conversation_id=e.conversation_id,
                payload=e.payload,
                processed=e.processed,
                retry_count=e.retry_count,
                created_at=e.created_at.isoformat(),
            )
            for e in events
        ]
    except Exception as e:
        logger.exception("Failed to get events")
        raise HTTPException(status_code=500, detail=str(e))


class PublishEventRequest(BaseModel):
    """Request to publish an event."""
    event_type: str
    doc_id: Optional[int] = None
    conversation_id: Optional[int] = None
    payload: Optional[dict] = None


@router.post("/events", response_model=EventResponse)
async def publish_event(request: PublishEventRequest, req: Request):
    """Publish an event to the event bus."""
    from app.persistence.database import db_service
    from app.persistence.event_bus import EventType

    try:
        event = await db_service.create_event(
            {
                "event_type": request.event_type,
                "doc_id": request.doc_id,
                "conversation_id": request.conversation_id,
                "payload": request.payload or {},
                "processed": False,
            }
        )
        logger.info(f"Event published: {request.event_type}")

        # Publish to event bus
        event_bus = getattr(req.app.state, "event_bus", None)
        if event_bus:
            await event_bus.publish(
                await event_bus._create_event(
                    EventType[request.event_type],
                    request.doc_id,
                    request.conversation_id,
                    request.payload or {},
                )
            )

        return EventResponse(
            id=event.id,
            event_type=event.event_type,
            doc_id=event.doc_id,
            conversation_id=event.conversation_id,
            payload=event.payload,
            processed=event.processed,
            retry_count=event.retry_count,
            created_at=event.created_at.isoformat(),
        )
    except Exception as e:
        logger.exception("Failed to publish event")
        raise HTTPException(status_code=500, detail=str(e))

