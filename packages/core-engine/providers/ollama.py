from typing import Any, AsyncIterator

from openai import AsyncOpenAI

from config import settings
from providers.base import BaseProvider, ProviderStreamChunk, StructuredOutputNotSupportedError, looks_like_unsupported_structured_output_error


class OllamaProvider(BaseProvider):
    """OpenAI-compatible provider for Ollama and any custom OpenAI-compatible endpoint."""

    def __init__(self, base_url: str | None = None, default_model: str | None = None) -> None:
        url = base_url or settings.ollama_base_url
        self.client = AsyncOpenAI(
            base_url=f"{url.rstrip('/')}/v1",
            api_key="ollama",
        )
        self.default_model = default_model or settings.ollama_default_model

    def supports_native_structured_output(self) -> bool:
        return True

    async def stream_chat(
        self,
        messages: list[dict],
        model: str | None = None,
        response_format: dict[str, Any] | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[ProviderStreamChunk]:
        request: dict[str, Any] = {
            "model": model or self.default_model,
            "messages": messages,
            "stream": True,
        }
        if response_format is not None:
            request["response_format"] = response_format
        if max_tokens is not None:
            request["max_tokens"] = max_tokens

        try:
            stream = await self.client.chat.completions.create(**request)
        except Exception as exc:
            if response_format is not None and looks_like_unsupported_structured_output_error(exc):
                raise StructuredOutputNotSupportedError(str(exc)) from exc
            raise

        async for chunk in stream:
            delta = chunk.choices[0].delta.content or ""
            if delta:
                yield delta

    async def chat(
        self,
        messages: list[dict],
        model: str | None = None,
        response_format: dict[str, Any] | None = None,
        max_tokens: int | None = None,
    ) -> str:
        request: dict[str, Any] = {
            "model": model or self.default_model,
            "messages": messages,
        }
        if response_format is not None:
            request["response_format"] = response_format
        if max_tokens is not None:
            request["max_tokens"] = max_tokens

        try:
            response = await self.client.chat.completions.create(**request)
        except Exception as exc:
            if response_format is not None and looks_like_unsupported_structured_output_error(exc):
                raise StructuredOutputNotSupportedError(str(exc)) from exc
            raise
        return response.choices[0].message.content or ""
