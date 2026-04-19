"""
Generic OpenAI-compatible provider.

Works with any service that implements the OpenAI /v1/chat/completions API:
    Ollama, Groq, OpenRouter, TogetherAI, z.ai, LM Studio, and any custom endpoint.

Only Anthropic and Azure require separate providers because their auth/API shape
differs from the OpenAI standard.
"""
from typing import Any, AsyncIterator
import logging

from openai import AsyncOpenAI

from providers.base import BaseProvider, ProviderStreamChunk, StructuredOutputNotSupportedError, looks_like_unsupported_structured_output_error

logger = logging.getLogger(__name__)


class GenericOpenAIProvider(BaseProvider):
    def __init__(
        self,
        base_url: str,
        api_key: str,
        default_model: str,
        extra_headers: dict[str, str] | None = None,
        supports_native_structured_output: bool = True,
    ) -> None:
        self.client = AsyncOpenAI(
            base_url=base_url.rstrip("/"),
            api_key=api_key,
            default_headers=extra_headers or {},
        )
        self.default_model = default_model
        self._supports_native_structured_output = supports_native_structured_output

    def supports_native_structured_output(self) -> bool:
        return self._supports_native_structured_output

    async def stream_chat(
        self,
        messages: list[dict],
        model: str | None = None,
        response_format: dict[str, object] | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[ProviderStreamChunk]:
        request: dict[str, object] = {
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
            # Defensive extraction: different providers/clients may return dict-like
            # or OpenAI objects. Protect against empty/malformed chunks.
            try:
                choices = None
                if hasattr(chunk, "choices"):
                    choices = chunk.choices
                elif isinstance(chunk, dict):
                    choices = chunk.get("choices")

                if not choices:
                    logger.debug("Received streaming chunk without choices: %r", chunk)
                    # Skip unknown meta chunks instead of crashing
                    continue

                first = choices[0]
                # Expose visible content and internal reasoning separately so the
                # orchestrator can keep reasoning inside execution details.
                content = ""
                reasoning = ""
                if isinstance(first, dict):
                    delta = first.get("delta") or {}
                    content = delta.get("content") or ""
                    reasoning = delta.get("reasoning_content") or ""
                else:
                    delta = getattr(first, "delta", None)
                    if delta is not None:
                        content = getattr(delta, "content", "") or ""
                        reasoning = getattr(delta, "reasoning_content", "") or ""

                payload: dict[str, Any] = {}
                if content:
                    payload["content"] = content
                if reasoning:
                    payload["reasoning"] = reasoning
                if payload:
                    yield payload
            except Exception as exc:  # be defensive and surface provider chunk shape
                logger.exception("Unexpected streaming chunk structure from provider: %s", exc)
                raise

    async def chat(
        self,
        messages: list[dict],
        model: str | None = None,
        response_format: dict[str, object] | None = None,
        max_tokens: int | None = None,
    ) -> str:
        request: dict[str, object] = {
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

        # Defensive extraction for providers that may return different shapes
        try:
            choices = getattr(response, "choices", None) or (response.get("choices") if isinstance(response, dict) else None)
            if not choices or len(choices) == 0:
                logger.error("Provider returned no choices in chat response: %r", response)
                raise Exception("Provider returned no choices in response")

            first = choices[0]
            if isinstance(first, dict):
                message = first.get("message") or {}
                content = message.get("content") or ""
            else:
                message = getattr(first, "message", None)
                if message is not None:
                    content = getattr(message, "content", None) or ""
                else:
                    content = ""

            return content or ""
        except Exception:
            logger.exception("Failed to parse provider chat response")
            raise
