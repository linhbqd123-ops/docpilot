"""LLM configuration loader.

Loads provider configuration from a JSON config file
and environment variables.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from llm_core.models.llm_models import LLMConfig, ProviderConfig, ProviderTier


DEFAULT_CONFIG_PATH = Path(__file__).parent.parent.parent.parent / "config" / "llm_config.json"


def load_llm_config(config_path: str | Path | None = None) -> LLMConfig:
    """Load LLM configuration from a JSON file.

    Environment variables can override api keys:
      LLM_PROVIDER_{NAME}_API_KEY
    """
    path = Path(config_path) if config_path else DEFAULT_CONFIG_PATH

    if not path.exists():
        # Return a default config with local Ollama provider
        return LLMConfig(
            providers=[
                ProviderConfig(
                    name="local",
                    base_url="http://localhost:11434/v1",
                    model="qwen2.5",
                    tier=ProviderTier.LOCAL,
                )
            ],
            default_provider="local",
        )

    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    config = LLMConfig(**data)

    # Override API keys from environment
    for provider in config.providers:
        env_key = f"LLM_PROVIDER_{provider.name.upper()}_API_KEY"
        env_val = os.environ.get(env_key)
        if env_val:
            provider.api_key = env_val

    return config
