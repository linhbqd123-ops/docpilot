"""Chat history management endpoints."""

from __future__ import annotations

from functools import lru_cache

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from config import settings
from services.chat_store import SQLiteChatStore
from services.document_store import SQLiteDocumentStore

router = APIRouter()


@lru_cache(maxsize=1)
def get_chat_store() -> SQLiteChatStore:
    return SQLiteChatStore(
        settings.chat_database_path,
        legacy_json_path=settings.legacy_chats_path,
    )


@lru_cache(maxsize=1)
def _get_document_store() -> SQLiteDocumentStore:
    return SQLiteDocumentStore(settings.chat_database_path)


class ChatMessage(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str
    role: str  # "user" | "assistant" | "system" | "error"
    content: str
    createdAt: int
    status: str | None = "sent"


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str | None = None
    name: str
    documentId: str
    messages: list[ChatMessage] = Field(default_factory=list)


class ChatUpdate(BaseModel):
    model_config = ConfigDict(extra="allow")

    name: str | None = None
    messages: list[ChatMessage] | None = None


@router.get("/chats")
async def list_chats(store: SQLiteChatStore = Depends(get_chat_store)):
    """Get all saved chats."""
    return {"chats": store.list_chats()}


@router.get("/chats/{chat_id}")
async def get_chat(chat_id: str, store: SQLiteChatStore = Depends(get_chat_store)):
    """Get a specific chat by ID."""
    chat = store.get_chat(chat_id)
    if not chat:
        raise HTTPException(status_code=404, detail="Chat not found")
    return {"chat": chat}


@router.post("/chats")
async def create_chat(body: ChatRequest, store: SQLiteChatStore = Depends(get_chat_store)):
    """Create or replace a chat snapshot using a stable client id."""
    import uuid

    saved = store.create_or_replace_chat(
        chat_id=body.id or str(uuid.uuid4()),
        name=body.name,
        document_id=body.documentId,
        messages=[message.model_dump() for message in body.messages],
    )
    return {"chat": saved}


@router.put("/chats/{chat_id}")
async def update_chat(
    chat_id: str,
    body: ChatUpdate,
    store: SQLiteChatStore = Depends(get_chat_store),
):
    """Update chat name or messages."""
    chat = store.update_chat(
        chat_id=chat_id,
        name=body.name,
        messages=[message.model_dump() for message in body.messages] if body.messages is not None else None,
    )
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    return {"chat": chat}


@router.delete("/chats/{chat_id}")
async def delete_chat(
    chat_id: str,
    store: SQLiteChatStore = Depends(get_chat_store),
    doc_store: SQLiteDocumentStore = Depends(_get_document_store),
):
    """Delete a chat. Blocked if the document has a pending unapplied revision."""
    chat = store.get_chat(chat_id)
    if chat is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    document_id: str | None = chat.get("documentId")
    if document_id:
        document = doc_store.get_document(document_id)
        if document:
            revision_status = document.get("revisionStatus") or ""
            pending_revision_id = document.get("pendingRevisionId") or ""
            if pending_revision_id or revision_status.upper() in ("PENDING", "STAGED"):
                raise HTTPException(
                    status_code=409,
                    detail="Please apply or discard the pending change before deleting this chat.",
                )
    store.delete_chat(chat_id)
    return {"ok": True}


@router.delete("/chats")
async def delete_all_chats(store: SQLiteChatStore = Depends(get_chat_store)):
    """Delete all chats (for debugging/reset)."""
    store.delete_all_chats()
    return {"ok": True}
