"""Repository layer for persistence operations.

Provides a clean abstraction for the agent to record conversations and events.
"""

from __future__ import annotations

import logging
from datetime import datetime

import sys
from pathlib import Path

# Import models from doc-mcp-service
sys.path.insert(0, str(Path(__file__).parent.parent.parent.parent / "doc-mcp-service"))

from app.persistence.models import (
    Document,
    DocumentCreate,
    Conversation,
    ConversationCreate,
    Event,
    EventCreate,
    Edit,
    EditCreate,
)
from app.persistence.database import db_service
from app.persistence.event_bus import event_bus, EventType, DomainEvent

logger = logging.getLogger(__name__)


class ConversationRepository:
    """Repository for conversation and event logging."""

    @staticmethod
    async def log_conversation(
        doc_id: int,
        user_message: str,
        agent_response: str,
        action: str | None = None,
        mode: str | None = None,
        provider: str | None = None,
        changes_summary: str | None = None,
        success: bool = True,
        tokens_used: int | None = None,
    ) -> Conversation | None:
        """Log a conversation turn."""
        try:
            conv = Conversation(
                doc_id=doc_id,
                user_message=user_message,
                agent_response=agent_response,
                action=action,
                mode=mode,
                provider_used=provider,
                changes_summary=changes_summary,
                success=success,
                tokens_used=tokens_used,
            )

            result = await db_service.create_conversation(conv)
            logger.info(
                f"Logged conversation #{result.id} for doc #{doc_id}: action={action}"
            )

            # Publish event for event-driven pipeline
            event = DomainEvent(
                event_type=EventType.CONVERSATION_COMPLETED,
                doc_id=doc_id,
                conversation_id=result.id,
                payload={
                    "action": action,
                    "success": success,
                    "tokens_used": tokens_used,
                },
            )
            await event_bus.publish(event)

            return result
        except Exception as e:
            logger.exception(f"Failed to log conversation: {e}")
            return None

    @staticmethod
    async def publish_event(
        event_type: EventType,
        doc_id: int | None = None,
        conversation_id: int | None = None,
        payload: dict | None = None,
    ) -> Event | None:
        """Publish an event to the event bus and store it."""
        try:
            if payload is None:
                payload = {}

            # Create event in database
            event_obj = Event(
                event_type=event_type.value,
                doc_id=doc_id,
                conversation_id=conversation_id,
                payload=payload,
                processed=False,
            )

            stored_event = await db_service.create_event(event_obj)
            logger.info(f"Event stored #{stored_event.id}: {event_type.value}")

            # Publish to event bus
            domain_event = DomainEvent(
                event_type=event_type,
                doc_id=doc_id,
                conversation_id=conversation_id,
                payload=payload,
            )
            await event_bus.publish(domain_event)

            return stored_event
        except Exception as e:
            logger.exception(f"Failed to publish event {event_type}: {e}")
            return None

    @staticmethod
    async def get_conversation_history(doc_id: int, limit: int = 50) -> list[Conversation]:
        """Get conversation history for a document."""
        try:
            return await db_service.get_conversations_for_doc(doc_id)
        except Exception as e:
            logger.exception(f"Failed to get conversation history: {e}")
            return []

    @staticmethod
    async def log_edit(
        conversation_id: int,
        document_version: int,
        changes: dict,
    ) -> Edit | None:
        """Record an edit operation."""
        try:
            edit = Edit(
                conversation_id=conversation_id,
                document_version=document_version,
                changes=changes,
            )

            result = await db_service.session_maker.add(edit)
            logger.info(f"Logged edit for conversation #{conversation_id}")
            return result
        except Exception as e:
            logger.exception(f"Failed to log edit: {e}")
            return None
