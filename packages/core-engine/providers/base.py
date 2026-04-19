from abc import ABC, abstractmethod
from typing import Any, AsyncIterator


class StructuredOutputNotSupportedError(RuntimeError):
    """Raised when a provider rejects native structured-output parameters."""


ProviderStreamChunk = str | dict[str, Any]


def looks_like_unsupported_structured_output_error(exc: Exception) -> bool:
    message = str(exc or "").lower()
    if not message:
        return False
    markers = (
        "response_format",
        "json_schema",
        "structured output",
        "structured_output",
        "unsupported",
        "not supported",
        "unknown parameter",
        "extra inputs are not permitted",
        "invalid parameter",
    )
    return any(marker in message for marker in markers)


class BaseProvider(ABC):
    def supports_native_structured_output(self) -> bool:
        return False

    @abstractmethod
    async def stream_chat(
        self,
        messages: list[dict],
        model: str | None = None,
        response_format: dict[str, Any] | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[ProviderStreamChunk]:
        """Stream chat response token by token."""
        ...

    @abstractmethod
    async def chat(
        self,
        messages: list[dict],
        model: str | None = None,
        response_format: dict[str, Any] | None = None,
        max_tokens: int | None = None,
    ) -> str:
        """Return complete chat response (non-streaming)."""
        ...
