from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from api import chats as chats_api
from api import documents as documents_api
from services.chat_store import SQLiteChatStore
from services.document_store import SQLiteDocumentStore


def build_document_app(document_store: SQLiteDocumentStore, chat_store: SQLiteChatStore) -> FastAPI:
    app = FastAPI()
    app.include_router(chats_api.router, prefix="/api")
    app.include_router(documents_api.router, prefix="/api")
    app.dependency_overrides[documents_api.get_document_store] = lambda: document_store
    app.dependency_overrides[chats_api.get_chat_store] = lambda: chat_store
    app.dependency_overrides[documents_api.get_chat_store] = lambda: chat_store
    return app


def test_document_crud_persists_across_store_reloads(tmp_path: Path) -> None:
    database_path = tmp_path / "docpilot.db"
    first_document_store = SQLiteDocumentStore(database_path)
    first_chat_store = SQLiteChatStore(database_path)

    create_payload = {
        "id": "doc-stable-1",
        "name": "Product brief.docx",
        "kind": "docx",
        "mimeType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "size": 2048,
        "status": "ready",
        "html": "<p>Initial content</p>",
        "outline": [{"id": "intro", "title": "Intro", "level": 1}],
        "wordCount": 2,
        "createdAt": 1710000000000,
        "updatedAt": 1710000005000,
        "backendDocId": "backend-doc-1",
        "documentSessionId": "session-1",
        "baseRevisionId": "rev-1",
        "currentRevisionId": "rev-1",
        "pendingRevisionId": None,
        "revisionStatus": None,
        "sessionState": "READY",
        "reviewPayload": None,
        "revisions": [{"revisionId": "rev-1", "status": "APPLIED", "summary": "Initial import"}],
        "error": None,
    }

    with TestClient(build_document_app(first_document_store, first_chat_store)) as client:
        create_response = client.post("/api/documents", json=create_payload)
        assert create_response.status_code == 200
        created_document = create_response.json()["document"]
        assert created_document["id"] == "doc-stable-1"
        assert created_document["documentSessionId"] == "session-1"

        fetch_response = client.get("/api/documents/doc-stable-1")
        assert fetch_response.status_code == 200
        assert fetch_response.json()["document"]["name"] == "Product brief.docx"

    second_document_store = SQLiteDocumentStore(database_path)
    second_chat_store = SQLiteChatStore(database_path)

    with TestClient(build_document_app(second_document_store, second_chat_store)) as client:
        list_response = client.get("/api/documents")
        assert list_response.status_code == 200
        documents = list_response.json()["documents"]
        assert [document["id"] for document in documents] == ["doc-stable-1"]

        update_response = client.post(
            "/api/documents",
            json={
                **create_payload,
                "name": "Product brief v2.docx",
                "html": "<p>Updated content</p>",
                "updatedAt": 1710000010000,
                "pendingRevisionId": "rev-2",
                "revisionStatus": "PENDING",
                "reviewPayload": {
                    "revisionId": "rev-2",
                    "status": "PENDING",
                    "summary": "Tightened wording",
                    "author": "assistant",
                    "scope": "minor",
                    "operationCount": 1,
                    "operations": [{"op": "replace", "description": "Tighten intro"}],
                },
                "revisions": [
                    {"revisionId": "rev-1", "status": "APPLIED", "summary": "Initial import"},
                    {"revisionId": "rev-2", "status": "PENDING", "summary": "Tightened wording"},
                ],
            },
        )
        assert update_response.status_code == 200
        updated_document = update_response.json()["document"]
        assert updated_document["name"] == "Product brief v2.docx"
        assert updated_document["pendingRevisionId"] == "rev-2"
        assert updated_document["reviewPayload"]["revisionId"] == "rev-2"


def test_deleting_document_also_cleans_up_linked_chats(tmp_path: Path) -> None:
    database_path = tmp_path / "docpilot.db"
    document_store = SQLiteDocumentStore(database_path)
    chat_store = SQLiteChatStore(database_path)

    with TestClient(build_document_app(document_store, chat_store)) as client:
        client.post(
            "/api/documents",
            json={
                "id": "doc-with-chat",
                "name": "Proposal.md",
                "kind": "markdown",
                "mimeType": "text/markdown",
                "size": 128,
                "status": "ready",
                "html": "<p>Proposal</p>",
                "outline": [],
                "wordCount": 1,
                "createdAt": 1711000000000,
                "updatedAt": 1711000000000,
                "backendDocId": None,
                "documentSessionId": None,
                "baseRevisionId": None,
                "currentRevisionId": None,
                "pendingRevisionId": None,
                "revisionStatus": None,
                "sessionState": None,
                "reviewPayload": None,
                "revisions": [],
                "error": None,
            },
        )
        chat_response = client.post(
            "/api/chats",
            json={
                "id": "chat-linked-1",
                "name": "Doc thread",
                "documentId": "doc-with-chat",
                "messages": [],
            },
        )
        assert chat_response.status_code == 200

        delete_response = client.delete("/api/documents/doc-with-chat")
        assert delete_response.status_code == 200
        assert delete_response.json() == {"ok": True}

        chats_response = client.get("/api/chats")
        assert chats_response.status_code == 200
        assert chats_response.json()["chats"] == []