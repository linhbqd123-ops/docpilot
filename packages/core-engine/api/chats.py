"""Chat history management endpoints."""

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from datetime import datetime
import json
import os
from pathlib import Path

router = APIRouter()

# Chat data persisted as JSON file (since this is single-user offline app)
CHATS_FILE = Path(__file__).resolve().parent.parent / "data" / "chats.json"


def _ensure_chats_file():
    """Ensure chats.json exists."""
    CHATS_FILE.parent.mkdir(parents=True, exist_ok=True)
    if not CHATS_FILE.exists():
        CHATS_FILE.write_text(json.dumps([]))


def _load_chats() -> list[dict]:
    """Load all chats from file."""
    _ensure_chats_file()
    try:
        return json.loads(CHATS_FILE.read_text())
    except Exception:
        return []


def _save_chats(chats: list[dict]) -> None:
    """Save chats to file."""
    _ensure_chats_file()
    CHATS_FILE.write_text(json.dumps(chats, indent=2, ensure_ascii=False))


class ChatMessage(BaseModel):
    id: str
    role: str  # "user" | "assistant" | "system" | "error"
    content: str
    createdAt: int
    status: str | None = "sent"


class ChatRequest(BaseModel):
    name: str
    documentId: str
    messages: list[ChatMessage] = []


class ChatUpdate(BaseModel):
    name: str | None = None
    messages: list[ChatMessage] | None = None


@router.get("/chats")
async def list_chats():
    """Get all saved chats."""
    chats = _load_chats()
    return {"chats": chats}


@router.get("/chats/{chat_id}")
async def get_chat(chat_id: str):
    """Get a specific chat by ID."""
    chats = _load_chats()
    chat = next((c for c in chats if c["id"] == chat_id), None)
    if not chat:
        raise HTTPException(status_code=404, detail="Chat not found")
    return {"chat": chat}


@router.post("/chats")
async def create_chat(body: ChatRequest):
    """Create a new chat."""
    import uuid
    from time import time

    chats = _load_chats()
    
    new_chat = {
        "id": str(uuid.uuid4()),
        "name": body.name,
        "documentId": body.documentId,
        "messages": [m.model_dump() for m in body.messages],
        "createdAt": int(time() * 1000),
        "updatedAt": int(time() * 1000),
    }
    
    chats.append(new_chat)
    _save_chats(chats)
    
    return {"chat": new_chat}


@router.put("/chats/{chat_id}")
async def update_chat(chat_id: str, body: ChatUpdate):
    """Update chat name or messages."""
    from time import time

    chats = _load_chats()
    chat_idx = next((i for i, c in enumerate(chats) if c["id"] == chat_id), None)
    
    if chat_idx is None:
        raise HTTPException(status_code=404, detail="Chat not found")
    
    if body.name is not None:
        chats[chat_idx]["name"] = body.name
    
    if body.messages is not None:
        chats[chat_idx]["messages"] = [m.model_dump() for m in body.messages]
    
    chats[chat_idx]["updatedAt"] = int(time() * 1000)
    _save_chats(chats)
    
    return {"chat": chats[chat_idx]}


@router.delete("/chats/{chat_id}")
async def delete_chat(chat_id: str):
    """Delete a chat."""
    chats = _load_chats()
    chats = [c for c in chats if c["id"] != chat_id]
    _save_chats(chats)
    
    return {"ok": True}


@router.delete("/chats")
async def delete_all_chats():
    """Delete all chats (for debugging/reset)."""
    _save_chats([])
    return {"ok": True}
