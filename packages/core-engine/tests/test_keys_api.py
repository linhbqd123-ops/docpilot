from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from api import keys as keys_api


def build_keys_app() -> FastAPI:
    app = FastAPI()
    app.include_router(keys_api.router)
    return app


def test_ollama_endpoint_can_be_saved_without_api_key(tmp_path: Path, monkeypatch) -> None:
    env_path = tmp_path / ".env"
    monkeypatch.setattr(keys_api, "_get_env_path", lambda: env_path)

    with TestClient(build_keys_app()) as client:
        save_response = client.post(
            "/api/keys/set",
            json={
                "provider": "ollama",
                "endpoint": "http://127.0.0.1:11434",
            },
        )
        assert save_response.status_code == 200
        body = save_response.json()
        assert body["provider"] == "ollama"
        assert body["has_key"] is False
        assert body["endpoint"] == "http://127.0.0.1:11434"
        assert body["resolved_endpoint"] == "http://127.0.0.1:11434"

        list_response = client.get("/api/keys/list")
        assert list_response.status_code == 200
        providers = {entry["provider"]: entry for entry in list_response.json()["providers"]}
        assert providers["ollama"]["endpoint"] == "http://127.0.0.1:11434"
        assert providers["ollama"]["has_key"] is False
        assert providers["openai"]["resolved_endpoint"] == "https://api.openai.com/v1"


def test_azure_endpoint_update_does_not_require_reentering_key(tmp_path: Path, monkeypatch) -> None:
    env_path = tmp_path / ".env"
    monkeypatch.setattr(keys_api, "_get_env_path", lambda: env_path)

    with TestClient(build_keys_app()) as client:
        first_save = client.post(
            "/api/keys/set",
            json={
                "provider": "azure",
                "key": "azure-secret-key",
                "endpoint": "https://first-resource.openai.azure.com",
            },
        )
        assert first_save.status_code == 200
        assert first_save.json()["has_key"] is True

        second_save = client.post(
            "/api/keys/set",
            json={
                "provider": "azure",
                "endpoint": "https://second-resource.openai.azure.com",
            },
        )
        assert second_save.status_code == 200
        body = second_save.json()
        assert body["has_key"] is True
        assert body["endpoint"] == "https://second-resource.openai.azure.com"
        assert body["resolved_endpoint"] == "https://second-resource.openai.azure.com"

        env_text = env_path.read_text(encoding="utf-8")
        assert "AZURE_OPENAI_API_KEY=encrypted:" in env_text
        assert "AZURE_OPENAI_ENDPOINT=https://second-resource.openai.azure.com" in env_text


def test_resetting_endpoint_override_keeps_existing_key(tmp_path: Path, monkeypatch) -> None:
    env_path = tmp_path / ".env"
    monkeypatch.setattr(keys_api, "_get_env_path", lambda: env_path)

    with TestClient(build_keys_app()) as client:
        create_response = client.post(
            "/api/keys/set",
            json={
                "provider": "openai",
                "key": "openai-secret-key",
                "endpoint": "https://proxy.internal/v1",
            },
        )
        assert create_response.status_code == 200
        assert create_response.json()["has_key"] is True

        delete_response = client.delete("/api/keys/delete/openai/endpoint")
        assert delete_response.status_code == 200

        list_response = client.get("/api/keys/list")
        assert list_response.status_code == 200
        providers = {entry["provider"]: entry for entry in list_response.json()["providers"]}
        assert providers["openai"]["has_key"] is True
        assert providers["openai"]["endpoint"] is None
        assert providers["openai"]["resolved_endpoint"] == "https://api.openai.com/v1"

        env_text = env_path.read_text(encoding="utf-8")
        assert "OPENAI_API_KEY=encrypted:" in env_text
        assert "OPENAI_BASE_URL=" not in env_text