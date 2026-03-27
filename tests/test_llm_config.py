"""Tests for the LLM config loader."""

from __future__ import annotations

import json
import os
import sys
import tempfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "llm-layer"))

from llm_core.core.config import load_llm_config
from llm_core.models.llm_models import ProviderTier


class TestLoadLLMConfig:
    def test_default_fallback(self, tmp_path):
        """When config file doesn't exist, return default local provider."""
        config = load_llm_config(tmp_path / "nonexistent.json")
        assert len(config.providers) == 1
        assert config.providers[0].name == "local"
        assert config.providers[0].tier == ProviderTier.LOCAL

    def test_load_from_file(self, tmp_path):
        cfg = {
            "providers": [
                {
                    "name": "groq",
                    "base_url": "https://api.groq.com/openai/v1",
                    "model": "mixtral",
                    "tier": "fast",
                    "api_key": "test-key",
                }
            ],
            "default_provider": "groq",
        }
        path = tmp_path / "config.json"
        path.write_text(json.dumps(cfg))

        config = load_llm_config(path)
        assert len(config.providers) == 1
        assert config.providers[0].name == "groq"
        assert config.providers[0].api_key == "test-key"

    def test_env_override(self, tmp_path, monkeypatch):
        """Environment variables override API keys from config."""
        cfg = {
            "providers": [
                {
                    "name": "openai",
                    "base_url": "https://api.openai.com/v1",
                    "model": "gpt-4",
                    "api_key": "from-file",
                }
            ],
        }
        path = tmp_path / "config.json"
        path.write_text(json.dumps(cfg))

        monkeypatch.setenv("LLM_PROVIDER_OPENAI_API_KEY", "from-env")
        config = load_llm_config(path)
        assert config.providers[0].api_key == "from-env"

    def test_legacy_ssl_fields_are_ignored(self, tmp_path):
        """Legacy SSL config keys should not break loading existing config files."""
        cfg = {
            "providers": [
                {
                    "name": "groq",
                    "base_url": "https://api.groq.com/openai/v1",
                    "model": "llama-3.3-70b-versatile",
                    "verify_ssl": False,
                    "ca_bundle_path": "C:/certs/corp-ca.pem",
                }
            ],
        }
        path = tmp_path / "config.json"
        path.write_text(json.dumps(cfg))

        config = load_llm_config(path)
        provider = config.providers[0]
        assert provider.name == "groq"
        assert provider.base_url == "https://api.groq.com/openai/v1"
