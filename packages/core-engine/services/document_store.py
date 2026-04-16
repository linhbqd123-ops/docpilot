from __future__ import annotations

import json
import logging
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)


def _current_millis() -> int:
    return int(time.time() * 1000)


def _as_int(value: Any, fallback: int) -> int:
    if isinstance(value, bool):
        return fallback
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return fallback
    return fallback


def _as_str(value: Any, fallback: str = "") -> str:
    if isinstance(value, str):
        return value
    if value is None:
        return fallback
    return str(value)


def _as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _as_dict(value: Any) -> dict[str, Any] | None:
    return value if isinstance(value, dict) else None


class SQLiteDocumentStore:
    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)
        self._lock = threading.RLock()
        self._initialize()

    def list_documents(self) -> list[dict[str, Any]]:
        with self._lock:
            with self._connect() as connection:
                rows = connection.execute(
                    """
                    SELECT id, payload_json
                    FROM documents
                    ORDER BY updated_at DESC, created_at DESC
                    """
                ).fetchall()
                return [self._deserialize_document(row) for row in rows]

    def get_document(self, document_id: str) -> dict[str, Any] | None:
        with self._lock:
            with self._connect() as connection:
                row = connection.execute(
                    """
                    SELECT id, payload_json
                    FROM documents
                    WHERE id = ?
                    """,
                    (document_id,),
                ).fetchone()
                return self._deserialize_document(row)

    def create_or_replace_document(self, raw_document: dict[str, Any]) -> dict[str, Any]:
        normalized = self._normalize_document_record(raw_document)
        with self._lock:
            with self._connect() as connection:
                connection.execute(
                    """
                    INSERT INTO documents (
                        id,
                        name,
                        kind,
                        status,
                        document_session_id,
                        backend_doc_id,
                        created_at,
                        updated_at,
                        payload_json
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        name = excluded.name,
                        kind = excluded.kind,
                        status = excluded.status,
                        document_session_id = excluded.document_session_id,
                        backend_doc_id = excluded.backend_doc_id,
                        updated_at = excluded.updated_at,
                        payload_json = excluded.payload_json
                    """,
                    (
                        normalized["id"],
                        normalized["name"],
                        normalized["kind"],
                        normalized["status"],
                        normalized.get("documentSessionId"),
                        normalized.get("backendDocId"),
                        normalized["createdAt"],
                        normalized["updatedAt"],
                        json.dumps(normalized, ensure_ascii=False),
                    ),
                )
                row = connection.execute(
                    """
                    SELECT id, payload_json
                    FROM documents
                    WHERE id = ?
                    """,
                    (normalized["id"],),
                ).fetchone()
                return self._deserialize_document(row)

    def delete_document(self, document_id: str) -> bool:
        with self._lock:
            with self._connect() as connection:
                cursor = connection.execute("DELETE FROM documents WHERE id = ?", (document_id,))
                return cursor.rowcount > 0

    def delete_all_documents(self) -> None:
        with self._lock:
            with self._connect() as connection:
                connection.execute("DELETE FROM documents")

    def _initialize(self) -> None:
        with self._lock:
            self.database_path.parent.mkdir(parents=True, exist_ok=True)
            with self._connect() as connection:
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS documents (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        status TEXT NOT NULL,
                        document_session_id TEXT,
                        backend_doc_id TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        payload_json TEXT NOT NULL
                    )
                    """
                )
                connection.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_documents_updated
                    ON documents(updated_at DESC, created_at DESC)
                    """
                )
                connection.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_documents_session
                    ON documents(document_session_id)
                    """
                )

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA journal_mode = WAL")
        connection.execute("PRAGMA synchronous = NORMAL")
        connection.execute("PRAGMA busy_timeout = 5000")
        return connection

    def _deserialize_document(self, row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None

        try:
            payload = json.loads(row["payload_json"])
        except json.JSONDecodeError:
            logger.warning("Failed to parse stored document payload for %s", row["id"])
            return None

        if not isinstance(payload, dict):
            return None

        return self._normalize_document_record(payload)

    def _normalize_document_record(self, raw_document: dict[str, Any]) -> dict[str, Any]:
        now = _current_millis()
        document_id = _as_str(raw_document.get("id"), "").strip() or f"doc_{now}"
        created_at = _as_int(raw_document.get("createdAt"), now)
        updated_at = _as_int(raw_document.get("updatedAt"), max(created_at, now))

        outline = []
        for index, item in enumerate(_as_list(raw_document.get("outline"))):
            record = _as_dict(item)
            if record is None:
                continue
            outline.append(
                {
                    "id": _as_str(record.get("id"), f"outline-{index + 1}"),
                    "title": _as_str(record.get("title"), "Untitled"),
                    "level": _as_int(record.get("level"), 1),
                }
            )

        revisions = []
        for item in _as_list(raw_document.get("revisions")):
            record = _as_dict(item)
            if record is None:
                continue
            revision_id = _as_str(record.get("revisionId"), "").strip()
            if not revision_id:
                continue
            normalized_revision = dict(record)
            normalized_revision["revisionId"] = revision_id
            revisions.append(normalized_revision)

        review_payload = _as_dict(raw_document.get("reviewPayload"))

        normalized = {
            "id": document_id,
            "name": _as_str(raw_document.get("name"), "Untitled document"),
            "kind": _as_str(raw_document.get("kind"), "unknown"),
            "mimeType": _as_str(raw_document.get("mimeType"), ""),
            "size": _as_int(raw_document.get("size"), 0),
            "status": _as_str(raw_document.get("status"), "ready"),
            "html": _as_str(raw_document.get("html"), ""),
            "outline": outline,
            "wordCount": _as_int(raw_document.get("wordCount"), 0),
            "createdAt": created_at,
            "updatedAt": updated_at,
            "revisions": revisions,
        }

        optional_string_fields = [
            "backendDocId",
            "documentSessionId",
            "baseRevisionId",
            "currentRevisionId",
            "pendingRevisionId",
            "revisionStatus",
            "sessionState",
            "error",
        ]
        for field_name in optional_string_fields:
            value = raw_document.get(field_name)
            if isinstance(value, str):
                normalized[field_name] = value
            elif value is None:
                normalized[field_name] = None if field_name.endswith("Id") or field_name in {"revisionStatus", "sessionState"} else None

        normalized["reviewPayload"] = review_payload
        return normalized