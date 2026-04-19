from typing import Any, AsyncIterator

from openai import AsyncAzureOpenAI

from config import settings
from providers.base import BaseProvider, ProviderStreamChunk, StructuredOutputNotSupportedError, looks_like_unsupported_structured_output_error


class AzureProvider(BaseProvider):
    """
    Azure OpenAI provider.
    Uses AsyncAzureOpenAI because Azure authentication (api_key + endpoint +
    deployment + api_version) differs from the standard OpenAI client.
    """

    def __init__(self, model_override: str | None = None) -> None:
        self.client = AsyncAzureOpenAI(
            api_key=settings.azure_openai_api_key,
            azure_endpoint=settings.azure_openai_endpoint,
            api_version=settings.azure_openai_api_version,
        )
        self.default_model = model_override or settings.azure_openai_deployment

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
