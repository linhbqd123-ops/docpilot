"""OpenAI-compatible LLM client.

Supports any provider that implements the OpenAI chat completions API,
including Ollama, LM Studio, Groq, OpenRouter, and OpenAI itself.
"""

from __future__ import annotations

import json
import logging
import os
import ssl
from urllib.parse import urlparse
from typing import Optional

import httpx

from llm_core.models.llm_models import LLMRequest, LLMResponse, ProviderConfig

logger = logging.getLogger(__name__)


def _short_text(text: Optional[str], limit: int = 2000) -> str:
    if not text:
        return ""
    normalized = " ".join(text.split())
    if len(normalized) <= limit:
        return normalized
    return normalized[:limit] + "...(truncated)"


class LLMClientError(Exception):
    pass


class LLMClient:
    """A unified client for OpenAI-compatible LLM APIs."""

    def __init__(self, provider: ProviderConfig):
        self.provider = provider
        verify_setting = self._build_verify_setting(provider.base_url)
        
        # Log the SSL verification setting
        if verify_setting is False:
            verify_msg = "SSL verification disabled"
        elif isinstance(verify_setting, ssl.SSLContext):
            verify_msg = "SSL verification: system trust store"
        else:
            verify_msg = "SSL verification enabled"
        
        # Log JSON strict mode support (if json_schema is supported)
        json_msg = ""
        if provider.supports_json_schema:
            strict_support = self._get_json_strict_setting(provider)
            json_msg = f", JSON mode: {'strict' if strict_support else 'best-effort'}"
        
        logger.info(
            "Initializing LLM client for %s with %s%s",
            provider.name,
            verify_msg,
            json_msg,
        )

        self._client = httpx.AsyncClient(
            base_url=provider.base_url.rstrip("/"),
            timeout=httpx.Timeout(provider.timeout, connect=10.0),
            verify=verify_setting,
            trust_env=False,  # Disable env CA override to respect verify setting
        )

    @staticmethod
    def _build_verify_setting(base_url: str) -> bool | ssl.SSLContext:
        global_verify_env = os.getenv("LLM_VERIFY_SSL")
        if global_verify_env is not None and global_verify_env.lower() in ("false", "0", "no"):
            return False

        scheme = urlparse(base_url).scheme.lower()
        if scheme == "https":
            # Match Node/Postman behavior by trusting the OS certificate store.
            return ssl.create_default_context()
        return True

    @staticmethod
    def _detect_strict_mode_support(model: str) -> bool:
        """Detect if model supports strict mode (constrained decoding).
        
        Strict mode is supported by:
        - openai/gpt-oss-* models (GPT-OSS)
        - meta-llama/llama-4-scout-* models
        
        Returns False for other models (use best-effort mode).
        """
        model_lower = model.lower()
        strict_models = [
            "openai/gpt-oss",
            "meta-llama/llama-4-scout"
        ]
        return any(strict in model_lower for strict in strict_models)

    @staticmethod
    def _get_json_strict_setting(provider: ProviderConfig) -> bool:
        """Determine if strict mode should be used.
        
        Priority:
        1. Explicit LLM_JSON_STRICT env var if set (true/false)
        2. Provider config supports_json_strict flag
        3. Auto-detect from model name
        """
        env_strict = os.getenv("LLM_JSON_STRICT", "auto").lower()
        
        # Explicit override (true/false)
        if env_strict in ("true", "1", "yes"):
            return True
        elif env_strict in ("false", "0", "no"):
            return False
        
        # Auto-detect or use provider config
        if provider.supports_json_strict:
            return True
        
        return LLMClient._detect_strict_mode_support(provider.model)

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
                # Format json_schema per Groq/OpenAI spec
                # Determine if strict mode is supported
                use_strict = self._get_json_strict_setting(self.provider)
                
                payload["response_format"] = {
                    "type": "json_schema",
                    "json_schema": {
                        "name": request.json_schema.get("name", "response_schema"),
                        "strict": use_strict,
                        "schema": request.json_schema
                    }
                }
                logger.debug(
                    f"Using json_schema mode for {self.provider.name} with schema: "
                    f"{request.json_schema.get('name', 'response_schema')}, strict: {use_strict}"
                )
            else:
                payload["response_format"] = {"type": "json_object"}
                logger.debug(
                    f"Using json_object mode for {self.provider.name} "
                    f"(json_schema not supported)"
                )

        headers = {"Content-Type": "application/json"}
        if self.provider.api_key:
            headers["Authorization"] = f"Bearer {self.provider.api_key}"

        try:
            logger.debug(
                "LLM payload provider=%s model=%s temp=%s max_tokens=%s response_format=%s message_count=%d",
                self.provider.name,
                payload.get("model"),
                payload.get("temperature"),
                payload.get("max_tokens"),
                payload.get("response_format"),
                len(messages),
            )
            logger.debug(
                "LLM messages provider=%s messages=%s",
                self.provider.name,
                _short_text(json.dumps(messages, ensure_ascii=False), 3000),
            )
            logger.debug(f"Sending request to {self.provider.name} at {self.provider.base_url}/chat/completions")
            response = await self._client.post(
                "/chat/completions",
                json=payload,
                headers=headers,
            )
            response.raise_for_status()
        except httpx.HTTPStatusError as e:
            logger.error(f"HTTP error from {self.provider.name}: {e.response.status_code} - {e.response.text}")
            raise LLMClientError(
                f"Provider {self.provider.name} returned {e.response.status_code}: {e.response.text}"
            )
        except httpx.RequestError as e:
            logger.error(f"Request error connecting to {self.provider.name}: {type(e).__name__}: {e}")
            raise LLMClientError(
                f"Connection error to provider {self.provider.name}: {e}"
            )

        data = response.json()
        logger.debug(
            "LLM raw response provider=%s status=%s body=%s",
            self.provider.name,
            response.status_code,
            _short_text(response.text, 4000),
        )

        content = data["choices"][0]["message"]["content"]
        usage = data.get("usage", {})
        logger.debug(
            "LLM parsed response provider=%s usage=%s content=%s",
            self.provider.name,
            usage,
            _short_text(content, 2000),
        )

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
