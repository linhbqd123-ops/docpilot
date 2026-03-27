"""LLM Router - Provider selection, fallback, and JSON enforcement.

Implements intelligent routing between LLM providers with:
- Manual provider selection
- Auto-selection by tier
- Fallback on failure
- JSON response validation and retry
"""

from __future__ import annotations

import json
import logging
from typing import Optional

from llm_core.models.llm_models import (
    LLMConfig,
    LLMRequest,
    LLMResponse,
    ProviderConfig,
    ProviderTier,
)
from llm_core.providers.openai_client import LLMClient, LLMClientError

logger = logging.getLogger(__name__)


def _short_text(text: Optional[str], limit: int = 2000) -> str:
    if not text:
        return ""
    normalized = " ".join(text.split())
    if len(normalized) <= limit:
        return normalized
    return normalized[:limit] + "...(truncated)"

JSON_ENFORCEMENT_SUFFIX = (
    "\n\nIMPORTANT: You MUST respond with valid JSON only. "
    "No markdown, no code fences, no explanation outside the JSON object. "
    "Start your response with { and end with }."
)


class LLMRouter:
    """Routes LLM requests to the appropriate provider with fallback logic."""

    def __init__(self, config: LLMConfig):
        self.config = config
        self._clients: dict[str, LLMClient] = {}
        self._initialize_clients()

    def _initialize_clients(self):
        for provider in self.config.providers:
            self._clients[provider.name] = LLMClient(provider)

    async def close(self):
        for client in self._clients.values():
            await client.close()

    def _validate_json_schema(self, data: dict, schema: dict) -> None:
        """Basic JSON schema validation for required fields and types."""
        # Check required fields
        required = schema.get("required", [])
        for field in required:
            if field not in data:
                raise ValueError(f"Missing required field: {field}")

        # Check field types (basic validation)
        properties = schema.get("properties", {})
        for field, field_schema in properties.items():
            if field in data:
                expected_type = field_schema.get("type")
                if expected_type:
                    actual_value = data[field]
                    if expected_type == "string" and not isinstance(actual_value, str):
                        raise ValueError(f"Field {field} should be string, got {type(actual_value)}")
                    elif expected_type == "number" and not isinstance(actual_value, (int, float)):
                        raise ValueError(f"Field {field} should be number, got {type(actual_value)}")
                    elif expected_type == "boolean" and not isinstance(actual_value, bool):
                        raise ValueError(f"Field {field} should be boolean, got {type(actual_value)}")
                    elif expected_type == "array" and not isinstance(actual_value, list):
                        raise ValueError(f"Field {field} should be array, got {type(actual_value)}")
                    elif expected_type == "object" and not isinstance(actual_value, dict):
                        raise ValueError(f"Field {field} should be object, got {type(actual_value)}")

    def _select_provider(self, request: LLMRequest) -> list[ProviderConfig]:
        """Select providers to try, in order of preference."""
        providers = list(self.config.providers)

        if request.provider_name:
            # Manual selection - put requested provider first
            selected = [p for p in providers if p.name == request.provider_name]
            fallbacks = [p for p in providers if p.name != request.provider_name and p.is_fallback]
            return selected + fallbacks

        if self.config.default_provider:
            selected = [p for p in providers if p.name == self.config.default_provider]
            fallbacks = [p for p in providers if p.name != self.config.default_provider]
            return selected + fallbacks

        # Auto-select by tier
        tier = self.config.auto_tier
        tier_match = [p for p in providers if p.tier == tier]
        others = [p for p in providers if p.tier != tier]

        return tier_match + others

    async def complete(self, request: LLMRequest) -> LLMResponse:
        """Send a completion request with routing and fallback."""
        providers = self._select_provider(request)

        if not providers:
            raise LLMClientError("No LLM providers configured")

        last_error: Optional[Exception] = None

        logger.info(f"Selected providers in order: {[p.name for p in providers]}")
        logger.debug(
            "LLM complete request provider_name=%s temp=%s max_tokens=%s json_mode=%s messages=%s system_prompt=%s",
            request.provider_name,
            request.temperature,
            request.max_tokens,
            request.json_mode,
            _short_text(json.dumps(request.messages, ensure_ascii=False), 1500),
            _short_text(request.system_prompt, 1500),
        )
        for provider in providers:
            client = self._clients.get(provider.name)
            logger.info(f"Attempting provider {provider.name} for request")
            if not client:
                logger.warning(f"No client found for provider {provider.name}")
                continue

            for attempt in range(self.config.max_retries):
                try:
                    logger.info(
                        f"Trying provider {provider.name} (attempt {attempt + 1})"
                    )
                    response = await client.chat_completion(request)
                    logger.info(f"Successfully got response from {provider.name}")
                    logger.debug(
                        "Provider response provider=%s model=%s usage=%s content=%s",
                        response.provider_used,
                        response.model_used,
                        response.usage,
                        _short_text(response.content, 2000),
                    )
                    return response
                except LLMClientError as e:
                    last_error = e
                    logger.warning(
                        f"Provider {provider.name} attempt {attempt + 1} failed: {e}"
                    )
                    continue

            logger.error(f"All retries exhausted for provider {provider.name}")

        raise LLMClientError(
            f"All providers failed. Last error: {last_error}"
        )

    async def complete_json(self, request: LLMRequest, json_schema: Optional[dict] = None) -> dict:
        """Send a completion request and enforce valid JSON response.

        Args:
            request: The LLM request
            json_schema: Optional JSON schema for structured outputs (if provider supports it)

        Retries with stricter prompting if the response is not valid JSON.
        """
        # Set JSON mode
        request.json_mode = True
        
        # Only use json_schema if the selected provider supports it
        selected_providers = self._select_provider(request)
        if selected_providers and json_schema:
            primary_provider = selected_providers[0]
            if not primary_provider.supports_json_schema:
                logger.debug(
                    f"Provider {primary_provider.name} does not support json_schema, "
                    f"will use json_object mode instead"
                )
                request.json_schema = None
            else:
                request.json_schema = json_schema
        else:
            request.json_schema = json_schema

        for attempt in range(self.config.json_retry_attempts + 1):
            logger.debug(
                "JSON completion attempt=%d schema=%s",
                attempt + 1,
                json_schema.get("name") if json_schema else None,
            )
            response = await self.complete(request)
            content = response.content.strip()

            # Strip markdown code fences if present
            if content.startswith("```"):
                lines = content.split("\n")
                # Remove first and last lines (fences)
                lines = [l for l in lines if not l.strip().startswith("```")]
                content = "\n".join(lines).strip()

            try:
                parsed = json.loads(content)
                # Validate against schema if provided
                if json_schema:
                    self._validate_json_schema(parsed, json_schema)
                return parsed
            except (json.JSONDecodeError, ValueError) as e:
                logger.warning(
                    f"JSON validation failed (attempt {attempt + 1}): {e}"
                )
                if attempt < self.config.json_retry_attempts:
                    # Strengthen the system prompt for retry
                    if request.system_prompt:
                        request.system_prompt += JSON_ENFORCEMENT_SUFFIX
                    else:
                        request.system_prompt = JSON_ENFORCEMENT_SUFFIX.strip()
                    # Add a user message clarifying
                    refine_message = (
                        "Your previous response was not valid JSON. "
                        "Please respond with ONLY a valid JSON object."
                    )
                    request.messages.append(
                        {
                            "role": "user",
                            "content": refine_message,
                        }
                    )
                    logger.debug(
                        "Applied prompt refine for retry. refined_system_prompt=%s refine_message=%s",
                        _short_text(request.system_prompt, 2000),
                        refine_message,
                    )

        raise LLMClientError(
            f"Failed to get valid JSON after {self.config.json_retry_attempts + 1} attempts"
        )

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        await self.close()
