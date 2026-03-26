"""Database initialization and session management.

Uses SQLModel for async SQLAlchemy with Pydantic validation.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from sqlmodel import SQLModel, select

from .models import Document, Conversation, Event

DATABASE_DIR = Path(__file__).parent.parent.parent / ".data"
DATABASE_DIR.mkdir(parents=True, exist_ok=True)
DATABASE_PATH = DATABASE_DIR / "docpilot.db"

# Use aiosqlite for async SQLite support
DATABASE_URL = f"sqlite+aiosqlite:///{DATABASE_PATH}"


class DatabaseService:
    """Manages database initialization and lifecycle."""

    def __init__(self, db_url: str = DATABASE_URL):
        self.db_url = db_url
        self.engine = None
        self.session_maker = None

    async def init(self):
        """Initialize database engine and create tables."""
        self.engine = create_async_engine(
            self.db_url,
            echo=False,
            future=True,
            pool_pre_ping=True,
        )

        # Create async session factory
        self.session_maker = async_sessionmaker(
            self.engine,
            class_=AsyncSession,
            expire_on_commit=False,
            future=True,
        )

        # Create tables
        async with self.engine.begin() as conn:
            await conn.run_sync(SQLModel.metadata.create_all)

    async def close(self):
        """Close database connection."""
        if self.engine:
            await self.engine.dispose()

    @asynccontextmanager
    async def get_session(self) -> AsyncGenerator[AsyncSession, None]:
        """Get an async database session."""
        if not self.session_maker:
            raise RuntimeError("Database not initialized")

        async with self.session_maker() as session:
            try:
                yield session
                await session.commit()
            except Exception as e:
                await session.rollback()
                raise e

    async def get_document(self, doc_id: int) -> Document | None:
        """Get a document by ID."""
        async with self.get_session() as session:
            stmt = select(Document).where(Document.id == doc_id)
            result = await session.execute(stmt)
            return result.scalar_one_or_none()

    async def create_document(self, doc: Document) -> Document:
        """Create a new document."""
        async with self.get_session() as session:
            session.add(doc)
            await session.commit()
            await session.refresh(doc)
            return doc

    async def get_conversations_for_doc(self, doc_id: int) -> list[Conversation]:
        """Get all conversations for a document."""
        async with self.get_session() as session:
            stmt = select(Conversation).where(Conversation.doc_id == doc_id)
            result = await session.execute(stmt)
            return result.scalars().all()

    async def create_conversation(self, conv: Conversation) -> Conversation:
        """Create a new conversation."""
        async with self.get_session() as session:
            session.add(conv)
            await session.commit()
            await session.refresh(conv)
            return conv

    async def get_pending_events(self, limit: int = 100) -> list[Event]:
        """Get unprocessed events."""
        async with self.get_session() as session:
            stmt = select(Event).where(Event.processed == False).limit(limit)
            result = await session.execute(stmt)
            return result.scalars().all()

    async def create_event(self, event: Event) -> Event:
        """Create a new event."""
        async with self.get_session() as session:
            session.add(event)
            await session.commit()
            await session.refresh(event)
            return event

    async def mark_event_processed(self, event_id: int, error: str | None = None):
        """Mark an event as processed."""
        from datetime import datetime
        async with self.get_session() as session:
            stmt = select(Event).where(Event.id == event_id)
            result = await session.execute(stmt)
            event = result.scalar_one_or_none()
            if event:
                event.processed = True
                event.processed_at = datetime.utcnow()
                event.error_message = error
                await session.commit()


# Global database service instance
db_service = DatabaseService()