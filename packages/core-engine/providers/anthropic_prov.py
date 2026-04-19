from typing import Any, AsyncIterator

import anthropic

from providers.base import BaseProvider, ProviderStreamChunk


class AnthropicProvider(BaseProvider):
    """
    Anthropic Claude provider.
    Uses Anthropic's own SDK because their Messages API (/v1/messages) is not
    compatible with the OpenAI /v1/chat/completions standard.
    """

    def __init__(self, api_key: str, default_model: str, base_url: str | None = None) -> None:
        self.client = anthropic.AsyncAnthropic(api_key=api_key, base_url=base_url or None)
        self.default_model = default_model

    def _split_messages(self, messages: list[dict]) -> tuple[str, list[dict]]:
        """Anthropic requires system prompt to be a separate top-level field."""
        system = ""
        conv: list[dict] = []
        for msg in messages:
            if msg.get("role") == "system":
                system += msg["content"] + "\n"
            else:
                conv.append(msg)
        return system.strip(), conv

    async def stream_chat(
        self,
        messages: list[dict],
        model: str | None = None,
        response_format: dict[str, Any] | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[ProviderStreamChunk]:
        system, conv = self._split_messages(messages)
        async with self.client.messages.stream(
            model=model or self.default_model,
            max_tokens=max_tokens or 8096,
            system=system,
            messages=conv,
        ) as stream:
            async for text in stream.text_stream:
                yield text

    async def chat(
        self,
        messages: list[dict],
        model: str | None = None,
        response_format: dict[str, Any] | None = None,
        max_tokens: int | None = None,
    ) -> str:
        system, conv = self._split_messages(messages)
        response = await self.client.messages.create(
            model=model or self.default_model,
            max_tokens=max_tokens or 8096,
            system=system,
            messages=conv,
        )
        return response.content[0].text
