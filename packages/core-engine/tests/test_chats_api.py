import json
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from api import chats as chats_api
from services.chat_store import SQLiteChatStore


def build_chat_app(store: SQLiteChatStore) -> FastAPI:
    app = FastAPI()
    app.include_router(chats_api.router, prefix="/api")
    app.dependency_overrides[chats_api.get_chat_store] = lambda: store
    return app


def test_chat_crud_persists_across_store_reloads(tmp_path: Path) -> None:
    database_path = tmp_path / "docpilot.db"
    first_store = SQLiteChatStore(database_path)

    create_payload = {
        "id": "chat-stable-1",
        "name": "Draft review",
        "documentId": "doc-42",
        "messages": [
            {
                "id": "msg-1",
                "role": "user",
                "content": "Please tighten the conclusion.",
                "createdAt": 1710000000000,
                "status": "sent",
            }
        ],
    }

    with TestClient(build_chat_app(first_store)) as client:
        create_response = client.post("/api/chats", json=create_payload)
        assert create_response.status_code == 200
        created_chat = create_response.json()["chat"]
        assert created_chat["id"] == "chat-stable-1"
        assert created_chat["documentId"] == "doc-42"
        assert created_chat["messages"][0]["content"] == "Please tighten the conclusion."

        fetch_response = client.get("/api/chats/chat-stable-1")
        assert fetch_response.status_code == 200
        fetched_chat = fetch_response.json()["chat"]
        assert fetched_chat == created_chat

    second_store = SQLiteChatStore(database_path)
    with TestClient(build_chat_app(second_store)) as client:
        list_response = client.get("/api/chats")
        assert list_response.status_code == 200
        chats = list_response.json()["chats"]
        assert len(chats) == 1
        assert chats[0]["id"] == "chat-stable-1"

        update_response = client.put(
            "/api/chats/chat-stable-1",
            json={
                "name": "Draft review v2",
                "messages": [
                    {
                        "id": "msg-1",
                        "role": "user",
                        "content": "Please tighten the conclusion.",
                        "createdAt": 1710000000000,
                        "status": "sent",
                    },
                    {
                        "id": "msg-2",
                        "role": "assistant",
                        "content": "I staged a tighter ending.",
                        "createdAt": 1710000005000,
                        "status": "sent",
                    },
                ],
            },
        )
        assert update_response.status_code == 200
        updated_chat = update_response.json()["chat"]
        assert updated_chat["name"] == "Draft review v2"
        assert len(updated_chat["messages"]) == 2
        assert updated_chat["createdAt"] == created_chat["createdAt"]
        assert updated_chat["updatedAt"] >= created_chat["updatedAt"]

        delete_response = client.delete("/api/chats/chat-stable-1")
        assert delete_response.status_code == 200
        assert delete_response.json() == {"ok": True}

        empty_response = client.get("/api/chats")
        assert empty_response.status_code == 200
        assert empty_response.json()["chats"] == []


def test_create_chat_is_idempotent_for_a_stable_client_id(tmp_path: Path) -> None:
    store = SQLiteChatStore(tmp_path / "docpilot.db")

    with TestClient(build_chat_app(store)) as client:
        first = client.post(
            "/api/chats",
            json={
                "id": "chat-duplicate-guard",
                "name": "First title",
                "documentId": "doc-7",
                "messages": [],
            },
        )
        assert first.status_code == 200
        first_chat = first.json()["chat"]

        second = client.post(
            "/api/chats",
            json={
                "id": "chat-duplicate-guard",
                "name": "Updated title",
                "documentId": "doc-7",
                "messages": [
                    {
                        "id": "msg-replay",
                        "role": "assistant",
                        "content": "Recovered from retry without duplicating the thread.",
                        "createdAt": 1710001000000,
                        "status": "sent",
                    }
                ],
            },
        )
        assert second.status_code == 200
        second_chat = second.json()["chat"]

        list_response = client.get("/api/chats")
        chats = list_response.json()["chats"]
        assert len(chats) == 1
        assert chats[0]["id"] == "chat-duplicate-guard"
        assert chats[0]["name"] == "Updated title"
        assert chats[0]["messages"][0]["id"] == "msg-replay"
        assert second_chat["createdAt"] == first_chat["createdAt"]


def test_legacy_json_is_migrated_into_sqlite_on_first_open(tmp_path: Path) -> None:
    database_path = tmp_path / "docpilot.db"
    legacy_path = tmp_path / "chats.json"
    legacy_path.write_text(
        json.dumps(
            [
                {
                    "id": "legacy-chat-1",
                    "name": "Legacy thread",
                    "documentId": "doc-legacy",
                    "createdAt": 1700000000000,
                    "updatedAt": 1700000005000,
                    "messages": [
                        {
                            "id": "legacy-msg-1",
                            "role": "user",
                            "content": "Legacy content survives migration.",
                            "createdAt": 1700000000000,
                            "status": "sent",
                        }
                    ],
                }
            ],
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    store = SQLiteChatStore(database_path, legacy_path)
    migrated = store.list_chats()

    assert [chat["id"] for chat in migrated] == ["legacy-chat-1"]
    assert migrated[0]["messages"][0]["content"] == "Legacy content survives migration."
    assert database_path.exists()
    assert not legacy_path.exists()
    assert legacy_path.with_suffix(".json.migrated").exists()

    reloaded = SQLiteChatStore(database_path, legacy_path)
    reloaded_chats = reloaded.list_chats()
    assert reloaded_chats == migrated