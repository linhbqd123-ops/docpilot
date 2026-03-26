"""OpenAI-compatible LLM client.

Supports any provider that implements the OpenAI chat completions API,
including Ollama, LM Studio, Groq, OpenRouter, and OpenAI itself.
"""

from __future__ import annotations

import json
import logging
import os
from typing import Optional

import httpx

from llm_core.models.llm_models import LLMRequest, LLMResponse, ProviderConfig

logger = logging.getLogger(__name__)


class LLMClientError(Exception):
    pass


class LLMClient:
    """A unified client for OpenAI-compatible LLM APIs."""

    def __init__(self, provider: ProviderConfig):
        self.provider = provider
        
        # SSL verification: Use True for production, but allow disable in development via env var
        # This is needed because some dev environments (Windows with self-signed certs) may have SSL issues
        verify_ssl = os.getenv("LLM_VERIFY_SSL", "true").lower() in ("true", "1", "yes")
        
        self._client = httpx.AsyncClient(
            base_url=provider.base_url.rstrip("/"),
            timeout=httpx.Timeout(provider.timeout, connect=10.0),
            verify=verify_ssl,  # SSL verification (True for production, can disable for dev)
        )

    async def close(self):
        await self._client.aclose()

    async def chat_completion(self, request: LLMRequest) -> LLMResponse:
        """Send a chat completion request to the provider."""
        messages = list(request.messages)

        if request.system_prompt:
            messages.insert(0, {"role": "system", "content": request.system_prompt})

        payload: dict = {
            "model": self.provider.model,
            "messages": messages,
            "temperature": request.temperature if request.temperature is not None else self.provider.temperature,
            "max_tokens": request.max_tokens if request.max_tokens is not None else self.provider.max_tokens,
        }

        if request.json_mode:
            if request.json_schema and self.provider.supports_json_schema:
                payload["response_format"] = {
                    "type": "json_schema",
                    "json_schema": request.json_schema
                }
            else:
                payload["response_format"] = {"type": "json_object"}

        headers = {"Content-Type": "application/json"}
        if self.provider.api_key:
            headers["Authorization"] = f"Bearer {self.provider.api_key}"

        try:
            response = await self._client.post(
                "/chat/completions",
                json=payload,
                headers=headers,
            )
            response.raise_for_status()
        except httpx.HTTPStatusError as e:
            raise LLMClientError(
                f"Provider {self.provider.name} returned {e.response.status_code}: {e.response.text}"
            )
        except httpx.RequestError as e:
            raise LLMClientError(
                f"Connection error to provider {self.provider.name}: {e}"
            )

        data = response.json()

        content = data["choices"][0]["message"]["content"]
        usage = data.get("usage", {})

        return LLMResponse(
            content=content,
            provider_used=self.provider.name,
            model_used=self.provider.model,
            usage=usage,
            raw_response=data,
        )

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        await self.close()
