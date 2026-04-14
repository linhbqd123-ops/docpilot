from typing import AsyncIterator

from openai import AsyncAzureOpenAI

from config import settings
from providers.base import BaseProvider


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
