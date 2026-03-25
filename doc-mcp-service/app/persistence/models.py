"""Database models for DocPilot persistence layer.

Uses SQLModel (SQLAlchemy + Pydantic) for type-safe ORM.
"""

from __future__ import annotations

from datetime import datetime
from typing import Optional

from sqlmodel import SQLModel, Field, JSON
from pydantic import BaseModel


# ===== Base Models =====

class DocumentBase(BaseModel):
    """Shared document attributes."""
    filename: str
    user_id: str = "local"  # Default to local dev
    metadata: dict = Field(default_factory=dict)


class DocumentCreate(DocumentBase):
    """Create a new document."""
    content_base64: str


class Document(DocumentBase, table=True):
    """A stored document/file."""
    __tablename__ = "documents"
    
    id: Optional[int] = Field(default=None, primary_key=True)
    content_base64: str  # Full .docx content
    uploaded_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)
    
    # For future vector search
    embedding: Optional[list[float]] = Field(default=None, sa_column_kwargs={"type": JSON})


# ===== Conversation =====

class ConversationCreate(BaseModel):
    """Create a new conversation."""
    doc_id: int
    user_message: str
    agent_response: str
    changes_summary: Optional[str] = None
    success: bool = True


class Conversation(BaseModel, table=True):
    """Agent-user conversation record."""
    __tablename__ = "conversations"
    
    id: Optional[int] = Field(default=None, primary_key=True)
    doc_id: int  # FK to Document
    user_message: str
    agent_response: str
    changes_summary: Optional[str] = None
    success: bool = True
    
    # Metadata for future analytics
    action: Optional[str] = None  # "rewrite", "improve", "tailor_cv", etc.
    mode: Optional[str] = None  # "preserve" or "rebuild"
    provider_used: Optional[str] = None  # LLM provider
    
    created_at: datetime = Field(default_factory=datetime.utcnow)
    tokens_used: Optional[int] = None  # For cost tracking


# ===== Event (for event-driven architecture) =====

class EventCreate(BaseModel):
    """Create a new event."""
    event_type: str  # "document_uploaded", "conversation_started", "embedding_generated", etc.
    conversation_id: Optional[int] = None
    doc_id: Optional[int] = None
    payload: dict = Field(default_factory=dict)
    processed: bool = False


class Event(EventCreate, table=True):
    """Event log for event-driven pipeline."""
    __tablename__ = "events"
    
    id: Optional[int] = Field(default=None, primary_key=True)
    event_type: str
    conversation_id: Optional[int] = None
    doc_id: Optional[int] = None
    payload: dict = Field(default_factory=dict, sa_column_kwargs={"type": JSON})
    processed: bool = False
    
    created_at: datetime = Field(default_factory=datetime.utcnow)
    processed_at: Optional[datetime] = None
    
    # For retries
    retry_count: int = 0
    error_message: Optional[str] = None


# ===== Edit History =====

class EditCreate(BaseModel):
    """Create an edit record."""
    conversation_id: int
    document_version: int  # Version number or document ID at time of edit
    changes: dict  # The BlockChange objects applied


class Edit(EditCreate, table=True):
    """Edit history for audit trail and undo/redo."""
    __tablename__ = "edits"
    
    id: Optional[int] = Field(default=None, primary_key=True)
    conversation_id: int
    document_version: int
    changes: dict = Field(sa_column_kwargs={"type": JSON})
    
    created_at: datetime = Field(default_factory=datetime.utcnow)


# ===== Query Models =====

class DocumentRead(DocumentBase):
    """For API responses."""
    id: int
    uploaded_at: datetime
    updated_at: datetime


class ConversationRead(BaseModel):
    """For API responses."""
    id: int
    doc_id: int
    user_message: str
    agent_response: str
    action: Optional[str]
    created_at: datetime


class EventRead(BaseModel):
    """For API responses."""
    id: int
    event_type: str
    doc_id: Optional[int]
    created_at: datetime
    processed: bool
