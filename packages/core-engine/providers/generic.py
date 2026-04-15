"""
Generic OpenAI-compatible provider.

Works with any service that implements the OpenAI /v1/chat/completions API:
  Ollama, Groq, OpenRouter, TogetherAI, z.ai, LM Studio, and any custom endpoint.

Only Anthropic and Azure require separate providers because their auth/API shape
differs from the OpenAI standard.
"""
from typing import AsyncIterator

from openai import AsyncOpenAI

from providers.base import BaseProvider


class GenericOpenAIProvider(BaseProvider):
    def __init__(
        self,
        base_url: str,
        api_key: str,
        default_model: str,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        self.client = AsyncOpenAI(
            base_url=base_url.rstrip("/"),
            api_key=api_key,
            default_headers=extra_headers or {},
        )
        self.default_model = default_model

    async def stream_chat(
        self,
        messages: list[dict],
        model: str | None = None,
    ) -> AsyncIterator[str]:
        key = getattr(self.client, "api_key", None)
        print(f"[stream_chat] Using API key: {key if key else 'None'}")
        stream = await self.client.chat.completions.create(
            model=model or self.default_model,
            messages=messages,
            stream=True,
        )
        async for chunk in stream:
            delta = chunk.choices[0].delta.content or ""
            if delta:
                yield delta

    async def chat(
        self,
        messages: list[dict],
        model: str | None = None,
    ) -> str:
        response = await self.client.chat.completions.create(
            model=model or self.default_model,
            messages=messages,
        )
        return response.choices[0].message.content or ""
