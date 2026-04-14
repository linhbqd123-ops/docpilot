from typing import AsyncIterator

from openai import AsyncOpenAI, AuthenticationError, NotFoundError

from config import settings
from providers.base import BaseProvider


class OllamaProvider(BaseProvider):
    """OpenAI-compatible provider for Ollama and any custom OpenAI-compatible endpoint."""

    def __init__(self, base_url: str | None = None, default_model: str | None = None) -> None:
        url = base_url or settings.ollama_base_url
        self.client = AsyncOpenAI(
            base_url=f"{url.rstrip('/')}/v1",
            api_key="ollama",
        )
        self.default_model = default_model or settings.ollama_default_model

    async def stream_chat(
        self,
        messages: list[dict],
        model: str | None = None,
    ) -> AsyncIterator[str]:
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
