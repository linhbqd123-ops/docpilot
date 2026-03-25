"""Event bus for event-driven architecture.

Provides a local in-memory event queue with async support.
Ready to scale to Redis Streams or AWS SQS later.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, asdict
from datetime import datetime
from typing import Callable, Optional
from enum import Enum

logger = logging.getLogger(__name__)


class EventType(str, Enum):
    """Standard event types in the system."""
    DOCUMENT_UPLOADED = "document_uploaded"
    CONVERSATION_STARTED = "conversation_started"
    CONVERSATION_COMPLETED = "conversation_completed"
    EMBEDDING_GENERATED = "embedding_generated"
    EMBEDDING_FAILED = "embedding_failed"
    VECTOR_INDEXED = "vector_indexed"


@dataclass
class DomainEvent:
    """Base class for all events."""
    event_type: EventType
    doc_id: Optional[int] = None
    conversation_id: Optional[int] = None
    payload: dict = None
    timestamp: datetime = None

    def __post_init__(self):
        if self.payload is None:
            self.payload = {}
        if self.timestamp is None:
            self.timestamp = datetime.utcnow()

    def to_dict(self) -> dict:
        """Convert to dictionary for storage."""
        return {
            "event_type": self.event_type.value,
            "doc_id": self.doc_id,
            "conversation_id": self.conversation_id,
            "payload": self.payload,
            "timestamp": self.timestamp.isoformat(),
        }


class EventHandler:
    """Handles a specific event type."""

    def __init__(self, event_type: EventType, handler_func: Callable):
        self.event_type = event_type
        self.handler_func = handler_func

    async def handle(self, event: DomainEvent):
        """Handle the event."""
        try:
            await self.handler_func(event)
        except Exception as e:
            logger.exception(f"Error handling {self.event_type}: {e}")


class EventBus:
    """Local in-memory event bus with async support.
    
    Architecture:
    - In-memory queue for local dev
    - Can be extended to Redis Streams for production
    - Handlers can be async for non-blocking event processing
    """

    def __init__(self):
        self.handlers: dict[EventType, list[EventHandler]] = {}
        self.queue: asyncio.Queue = asyncio.Queue()
        self.running = False

    def subscribe(self, event_type: EventType, handler: Callable):
        """Subscribe a handler to an event type."""
        if event_type not in self.handlers:
            self.handlers[event_type] = []

        handler_obj = EventHandler(event_type, handler)
        self.handlers[event_type].append(handler_obj)
        logger.info(f"Registered handler for {event_type}")

    async def publish(self, event: DomainEvent):
        """Publish an event asynchronously."""
        await self.queue.put(event)
        logger.debug(f"Event published: {event.event_type} - doc_id={event.doc_id}")

    async def start(self):
        """Start the event bus (processes events from queue)."""
        self.running = True
        logger.info("Event bus started")

        try:
            while self.running:
                try:
                    # Wait for event with timeout to allow graceful shutdown
                    event: DomainEvent = await asyncio.wait_for(
                        self.queue.get(), timeout=1.0
                    )
                except asyncio.TimeoutError:
                    continue

                # Get handlers for this event type
                handlers = self.handlers.get(event.event_type, [])

                if not handlers:
                    logger.debug(f"No handlers for {event.event_type}")
                    continue

                # Execute handlers concurrently
                await asyncio.gather(
                    *[h.handle(event) for h in handlers],
                    return_exceptions=True,
                )

        except asyncio.CancelledError:
            logger.info("Event bus stopped")
        except Exception as e:
            logger.exception(f"Event bus error: {e}")

    async def stop(self):
        """Stop the event bus."""
        self.running = False
        logger.info("Stopping event bus...")


# Global event bus instance
event_bus = EventBus()
