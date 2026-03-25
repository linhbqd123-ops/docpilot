"""LLM provider and request/response models."""

from __future__ import annotations

from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


class ProviderTier(str, Enum):
    LOCAL = "local"
    FAST = "fast"
    QUALITY = "quality"
    CUSTOM = "custom"


class ProviderConfig(BaseModel):
    """Configuration for a single LLM provider."""
    name: str
    base_url: str
    api_key: Optional[str] = None
    model: str
    tier: ProviderTier = ProviderTier.CUSTOM
    max_tokens: int = 4096
    temperature: float = 0.3
    timeout: int = 120
    is_fallback: bool = False


class LLMRequest(BaseModel):
    """A request to the LLM."""
    messages: list[dict] = Field(default_factory=list)
    system_prompt: Optional[str] = None
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    json_mode: bool = False
    provider_name: Optional[str] = None  # None = auto-select


class LLMResponse(BaseModel):
    """Response from the LLM."""
    content: str
    provider_used: str
    model_used: str
    usage: dict = Field(default_factory=dict)
    raw_response: Optional[dict] = None


class LLMConfig(BaseModel):
    """Top-level LLM configuration."""
    providers: list[ProviderConfig] = Field(default_factory=list)
    default_provider: Optional[str] = None
    auto_tier: ProviderTier = ProviderTier.FAST
    max_retries: int = 3
    json_retry_attempts: int = 2
