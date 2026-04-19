from typing import Any

from fastapi import FastAPI
from fastapi.testclient import TestClient

from api import agent as agent_api


def build_agent_app() -> FastAPI:
    app = FastAPI()
    app.include_router(agent_api.router, prefix="/api")
    return app


class FakeAgentDocMcpClient:
    def __init__(self, *, source_html: str | None, projection_html: str = "<article><p>Projection</p></article>") -> None:
        self.source_html = source_html
        self.projection_html = projection_html
        self.source_calls: list[str] = []
        self.projection_calls: list[str] = []
        self.preview_calls: list[str] = []
        self.apply_calls: list[str] = []
        self.restore_calls: list[str] = []

    async def get_source_html(self, session_id: str) -> str:
        self.source_calls.append(session_id)
        if self.source_html is None:
            raise RuntimeError("source html missing")
        return self.source_html

    async def get_html_projection(self, session_id: str, fragment: bool = True) -> str:
        self.projection_calls.append(f"{session_id}:{fragment}")
        return self.projection_html

    async def get_session_summary(self, session_id: str) -> dict[str, Any]:
        return {"session_id": session_id, "state": "READY", "current_revision_id": "rev-1", "word_count": 42}

    async def list_session_revisions(self, session_id: str, status: str | None = None) -> list[dict[str, Any]]:
        return [{"revision_id": "rev-1", "status": "APPLIED", "summary": "Imported"}]

    async def get_revision(self, revision_id: str) -> dict[str, Any]:
        return {"revision_id": revision_id, "session_id": "session-1", "status": "PENDING"}

    async def preview_revision(self, revision_id: str) -> dict[str, Any]:
        self.preview_calls.append(revision_id)
        return {
            "revision_id": revision_id,
            "session_id": "session-1",
            "available": True,
            "diff": {
                "target_revision_id": revision_id,
                "text_edit_count": 1,
                "style_edit_count": 1,
                "layout_edit_count": 0,
                "has_conflicts": False,
                "text_diffs": [
                    {
                        "block_id": "block-1",
                        "change_type": "REPLACE",
                        "old_text": "Before",
                        "new_text": "After",
                    }
                ],
                "style_diffs": [
                    {
                        "block_id": "block-1",
                        "property": "alignment",
                        "old_value": "LEFT",
                        "new_value": "CENTER",
                    }
                ],
                "layout_diffs": [],
            },
        }

    async def apply_revision(self, revision_id: str) -> dict[str, Any]:
        self.apply_calls.append(revision_id)
        return {"revision_id": revision_id, "status": "APPLIED", "current_revision_id": revision_id}

    async def restore_revision(self, revision_id: str) -> dict[str, Any]:
        self.restore_calls.append(revision_id)
        return {"revision_id": revision_id, "status": "APPLIED", "current_revision_id": revision_id}


def test_projection_endpoint_prefers_fidelity_source_html(monkeypatch) -> None:
    fake_client = FakeAgentDocMcpClient(source_html="<article><p>Fidelity source</p></article>")
    monkeypatch.setattr(agent_api, "_doc_mcp_client", lambda: fake_client)

    with TestClient(build_agent_app()) as client:
        response = client.get("/api/agent/sessions/session-1/projection")

    assert response.status_code == 200
    payload = response.json()
    assert payload["html"] == "<article><p>Fidelity source</p></article>"
    assert payload["sourceHtml"] == "<article><p>Fidelity source</p></article>"
    assert fake_client.source_calls == ["session-1"]
    assert fake_client.projection_calls == []


def test_projection_endpoint_fails_when_source_html_is_unavailable(monkeypatch) -> None:
    fake_client = FakeAgentDocMcpClient(source_html=None)
    monkeypatch.setattr(agent_api, "_doc_mcp_client", lambda: fake_client)

    with TestClient(build_agent_app()) as client:
        response = client.get("/api/agent/sessions/session-1/projection")

    assert response.status_code == 500
    assert fake_client.source_calls == ["session-1"]
    assert fake_client.projection_calls == []


def test_apply_revision_returns_fidelity_html_payload(monkeypatch) -> None:
    fake_client = FakeAgentDocMcpClient(source_html="<article><p>Edited fidelity source</p></article>")
    monkeypatch.setattr(agent_api, "_doc_mcp_client", lambda: fake_client)

    with TestClient(build_agent_app()) as client:
        response = client.post("/api/agent/revisions/rev-2/apply")

    assert response.status_code == 200
    payload = response.json()
    assert payload["documentSessionId"] == "session-1"
    assert payload["html"] == "<article><p>Edited fidelity source</p></article>"
    assert payload["sourceHtml"] == "<article><p>Edited fidelity source</p></article>"
    assert payload["result"]["status"] == "APPLIED"
    assert fake_client.apply_calls == ["rev-2"]

def test_preview_revision_returns_pending_preview_payload(monkeypatch) -> None:
    fake_client = FakeAgentDocMcpClient(source_html="<article><p>Current fidelity source</p></article>")
    monkeypatch.setattr(agent_api, "_doc_mcp_client", lambda: fake_client)

    with TestClient(build_agent_app()) as client:
        response = client.get("/api/agent/revisions/rev-preview/preview")

    assert response.status_code == 200
    payload = response.json()
    assert payload["revisionId"] == "rev-preview"
    assert payload["documentSessionId"] == "session-1"
    assert payload["available"] is True
    assert payload["diff"]["style_edit_count"] == 1
    assert payload["diff"]["text_diffs"][0]["new_text"] == "After"
    assert fake_client.preview_calls == ["rev-preview"]


def test_restore_revision_returns_fidelity_html_payload(monkeypatch) -> None:
    fake_client = FakeAgentDocMcpClient(source_html="<article><p>Restored fidelity source</p></article>")
    monkeypatch.setattr(agent_api, "_doc_mcp_client", lambda: fake_client)

    with TestClient(build_agent_app()) as client:
        response = client.post("/api/agent/revisions/rev-restore/restore")

    assert response.status_code == 200
    payload = response.json()
    assert payload["documentSessionId"] == "session-1"
    assert payload["html"] == "<article><p>Restored fidelity source</p></article>"
    assert payload["sourceHtml"] == "<article><p>Restored fidelity source</p></article>"
    assert payload["result"]["status"] == "APPLIED"
    assert fake_client.restore_calls == ["rev-restore"]