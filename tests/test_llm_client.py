from __future__ import annotations

import ssl
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "llm-layer"))

from llm_core.models.llm_models import ProviderConfig
from llm_core.providers.openai_client import LLMClient


class DummyAsyncClient:
    def __init__(self, **kwargs):
        self.kwargs = kwargs

    async def aclose(self):
        return None


def test_https_provider_uses_system_trust_store(monkeypatch):
    captured: dict = {}

    def fake_async_client(**kwargs):
        captured.update(kwargs)
        return DummyAsyncClient(**kwargs)

    monkeypatch.setattr("llm_core.providers.openai_client.httpx.AsyncClient", fake_async_client)

    LLMClient(
        ProviderConfig(
            name="groq",
            base_url="https://api.groq.com/openai/v1",
            model="llama-3.3-70b-versatile",
        )
    )

    assert isinstance(captured["verify"], ssl.SSLContext)
    assert captured["trust_env"] is True


def test_http_provider_skips_tls_context(monkeypatch):
    captured: dict = {}

    def fake_async_client(**kwargs):
        captured.update(kwargs)
        return DummyAsyncClient(**kwargs)

    monkeypatch.setattr("llm_core.providers.openai_client.httpx.AsyncClient", fake_async_client)

    LLMClient(
        ProviderConfig(
            name="local",
            base_url="http://host.docker.internal:11434/v1",
            model="qwen2.5",
        )
    )

    assert captured["verify"] is True
    assert captured["trust_env"] is True


def test_https_provider_can_disable_ssl_via_env(monkeypatch):
    captured: dict = {}

    def fake_async_client(**kwargs):
        captured.update(kwargs)
        return DummyAsyncClient(**kwargs)

    monkeypatch.setattr("llm_core.providers.openai_client.httpx.AsyncClient", fake_async_client)
    monkeypatch.setenv("LLM_VERIFY_SSL", "false")

    LLMClient(
        ProviderConfig(
            name="openrouter",
            base_url="https://openrouter.ai/api/v1",
            model="anthropic/claude-3.5-sonnet",
        )
    )

    assert captured["verify"] is False
    assert captured["trust_env"] is True