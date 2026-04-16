"""Provider registry for DocPilot Core Engine.

Architecture:
  - All providers that implement the OpenAI /v1/chat/completions standard
    (Ollama, OpenAI, Groq, OpenRouter, TogetherAI, z.ai, custom) share a
    single GenericOpenAIProvider — no separate SDK needed.
  - Anthropic uses its own SDK (their Messages API is not OpenAI-compatible).
  - Azure uses AsyncAzureOpenAI (different auth model).
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Literal

from config import settings
from providers.base import BaseProvider

ProviderName = Literal[
    "ollama",
    "openai",
    "groq",
    "openrouter",
    "together",
    "zai",
    "anthropic",
    "azure",
    "custom",
]


def _get_provider_endpoint_env_name(provider: str) -> str:
    """Get endpoint env var name (matches api/keys.py naming convention)."""
    if provider == "azure":
        return "AZURE_OPENAI_ENDPOINT"
    elif provider == "openai":
        return "OPENAI_BASE_URL"
    elif provider == "groq":
        return "GROQ_BASE_URL"
    elif provider == "openrouter":
        return "OPENROUTER_BASE_URL"
    elif provider == "together":
        return "TOGETHER_BASE_URL"
    elif provider == "zai":
        return "ZAI_BASE_URL"
    elif provider == "anthropic":
        return "ANTHROPIC_BASE_URL"
    elif provider == "ollama":
        return "OLLAMA_BASE_URL"
    elif provider == "custom":
        return "CUSTOM_BASE_URL"
    else:
        return f"{provider.upper()}_ENDPOINT"


@dataclass
class ProviderConfig:
    display_name: str
    kind: Literal["generic", "anthropic", "azure"] = "generic"
    # Static defaults (can all be overridden by env vars)
    base_url: str = ""
    base_url_env: str = ""
    api_key: str = ""
    api_key_env: str = ""
    default_model: str = ""
    default_model_env: str = ""
    # Optional extra HTTP headers required by some providers (e.g. OpenRouter)
    extra_headers: dict[str, str] = field(default_factory=dict)

    def resolve_base_url(self, provider_name: str = "") -> str:
        """Resolve provider endpoint URL (user-provided via frontend or default)."""
        # Map base_url_env var names to settings attributes
        base_url_map = {
            "OLLAMA_BASE_URL": settings.ollama_base_url,
            "OPENAI_BASE_URL": settings.openai_base_url,
            "GROQ_BASE_URL": settings.groq_base_url,
            "OPENROUTER_BASE_URL": settings.openrouter_base_url,
            "TOGETHER_BASE_URL": settings.together_base_url,
            "ZAI_BASE_URL": settings.zai_base_url,
            "ANTHROPIC_BASE_URL": settings.anthropic_base_url,
            "CUSTOM_BASE_URL": settings.custom_base_url,
            "AZURE_OPENAI_ENDPOINT": settings.azure_openai_endpoint,
        }
        
        # Fall back to base_url_env
        if self.base_url_env and self.base_url_env in base_url_map:
            resolved = base_url_map[self.base_url_env].strip()
            if resolved:
                return resolved
        
        return self.base_url

    def resolve_api_key(self) -> str:
        """Resolve API key from settings (which auto-decrypts encrypted keys)."""
        if self.api_key_env:
            # Map env var names to settings attributes
            env_map = {
                "OPENAI_API_KEY": settings.openai_api_key,
                "GROQ_API_KEY": settings.groq_api_key,
                "OPENROUTER_API_KEY": settings.openrouter_api_key,
                "TOGETHER_API_KEY": settings.together_api_key,
                "ZAI_API_KEY": settings.zai_api_key,
                "ANTHROPIC_API_KEY": settings.anthropic_api_key,
                "AZURE_OPENAI_API_KEY": settings.azure_openai_api_key,
                "CUSTOM_API_KEY": settings.custom_api_key,
            }
            return env_map.get(self.api_key_env, "").strip()
        return self.api_key.strip()

    def resolve_model(self) -> str:
        """Resolve default model from settings."""
        if self.default_model_env:
            # Map model env var names to settings attributes
            model_map = {
                "OPENAI_DEFAULT_MODEL": settings.openai_default_model,
                "GROQ_DEFAULT_MODEL": settings.groq_default_model,
                "OPENROUTER_DEFAULT_MODEL": settings.openrouter_default_model,
                "TOGETHER_DEFAULT_MODEL": settings.together_default_model,
                "ZAI_DEFAULT_MODEL": settings.zai_default_model,
                "ANTHROPIC_DEFAULT_MODEL": settings.anthropic_default_model,
                "OLLAMA_DEFAULT_MODEL": settings.ollama_default_model,
                "CUSTOM_DEFAULT_MODEL": settings.custom_default_model,
            }
            return model_map.get(self.default_model_env, self.default_model)
        return self.default_model


# ---------------------------------------------------------------------------
# Provider registry
# ---------------------------------------------------------------------------

REGISTRY: dict[str, ProviderConfig] = {
    "ollama": ProviderConfig(
        display_name="Ollama (local)",
        base_url="http://localhost:11434/v1",
        base_url_env="OLLAMA_BASE_URL",  # will append /v1 if missing — see get_provider
        api_key="ollama",
        default_model="llama3.2",
        default_model_env="OLLAMA_DEFAULT_MODEL",
    ),
    "openai": ProviderConfig(
        display_name="OpenAI",
        base_url="https://api.openai.com/v1",
        base_url_env="OPENAI_BASE_URL",
        api_key_env="OPENAI_API_KEY",
        default_model="gpt-4o",
        default_model_env="OPENAI_DEFAULT_MODEL",
    ),
    "groq": ProviderConfig(
        display_name="Groq",
        base_url="https://api.groq.com/openai/v1",
        base_url_env="GROQ_BASE_URL",
        api_key_env="GROQ_API_KEY",
        default_model="llama-3.3-70b-versatile",
        default_model_env="GROQ_DEFAULT_MODEL",
    ),
    "openrouter": ProviderConfig(
        display_name="OpenRouter",
        base_url="https://openrouter.ai/api/v1",
        base_url_env="OPENROUTER_BASE_URL",
        api_key_env="OPENROUTER_API_KEY",
        default_model="meta-llama/llama-3.3-70b-instruct",
        default_model_env="OPENROUTER_DEFAULT_MODEL",
        # OpenRouter recommends these headers for ranking / analytics
        extra_headers={
            "HTTP-Referer": "https://docpilot.app",
            "X-Title": "DocPilot",
        },
    ),
    "together": ProviderConfig(
        display_name="TogetherAI",
        base_url="https://api.together.xyz/v1",
        base_url_env="TOGETHER_BASE_URL",
        api_key_env="TOGETHER_API_KEY",
        default_model="meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo",
        default_model_env="TOGETHER_DEFAULT_MODEL",
    ),
    "zai": ProviderConfig(
        display_name="z.ai",
        base_url="https://api.z.ai/api/v1",
        base_url_env="ZAI_BASE_URL",
        api_key_env="ZAI_API_KEY",
        default_model="zai-v1",
        default_model_env="ZAI_DEFAULT_MODEL",
    ),
    "anthropic": ProviderConfig(
        display_name="Anthropic",
        kind="anthropic",
        base_url="https://api.anthropic.com",
        base_url_env="ANTHROPIC_BASE_URL",
        api_key_env="ANTHROPIC_API_KEY",
        default_model="claude-3-5-sonnet-20241022",
        default_model_env="ANTHROPIC_DEFAULT_MODEL",
    ),
    "azure": ProviderConfig(
        display_name="Azure OpenAI",
        kind="azure",
        base_url_env="AZURE_OPENAI_ENDPOINT",
        # Azure credentials come from dedicated env vars in config.py
    ),
    "custom": ProviderConfig(
        display_name="Custom (OpenAI-compatible)",
        base_url_env="CUSTOM_BASE_URL",
        api_key_env="CUSTOM_API_KEY",
        default_model="",
        default_model_env="CUSTOM_DEFAULT_MODEL",
    ),
}


# ---------------------------------------------------------------------------
# Factory
# ---------------------------------------------------------------------------


def get_provider(name: ProviderName, model_override: str | None = None) -> BaseProvider:
    """
    Return a provider instance for the given name.
    model_override lets the caller specify an exact model (e.g. the frontend
    can send model="grok-2" when provider="custom").
    """
    cfg = REGISTRY.get(name)
    if cfg is None:
        raise ValueError(
            f"Unknown provider '{name}'. "
            f"Supported: {', '.join(REGISTRY)}"
        )

    if cfg.kind == "anthropic":
        from providers.anthropic_prov import AnthropicProvider
        return AnthropicProvider(
            api_key=cfg.resolve_api_key(),
            default_model=model_override or cfg.resolve_model(),
            base_url=cfg.resolve_base_url(name),
        )

    if cfg.kind == "azure":
        from providers.azure_prov import AzureProvider
        return AzureProvider(model_override=model_override)

    # All other providers: generic OpenAI-compatible
    from providers.generic import GenericOpenAIProvider

    base_url = cfg.resolve_base_url(name)
    # Ollama env var stores just the host; append /v1 if not present
    if name == "ollama" and not base_url.rstrip("/").endswith("/v1"):
        base_url = base_url.rstrip("/") + "/v1"

    return GenericOpenAIProvider(
        base_url=base_url,
        api_key=cfg.resolve_api_key(),
        default_model=model_override or cfg.resolve_model(),
        extra_headers=cfg.extra_headers,
    )


def list_providers() -> list[dict]:
    """Return display info for all registered providers (for /api/providers endpoint)."""
    return [
        {"id": pid, "name": cfg.display_name, "kind": cfg.kind}
        for pid, cfg in REGISTRY.items()
    ]
