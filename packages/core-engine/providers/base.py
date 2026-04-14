from abc import ABC, abstractmethod
from typing import AsyncIterator


class BaseProvider(ABC):
    @abstractmethod
    async def stream_chat(
        self,
        messages: list[dict],
        model: str | None = None,
    ) -> AsyncIterator[str]:
        """Stream chat response token by token."""
        ...

    @abstractmethod
    async def chat(
        self,
        messages: list[dict],
        model: str | None = None,
    ) -> str:
        """Return complete chat response (non-streaming)."""
        ...
