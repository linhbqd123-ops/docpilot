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


class SQLiteChatStore:
    def __init__(self, database_path: str | Path, legacy_json_path: str | Path | None = None) -> None:
        self.database_path = Path(database_path)
        self.legacy_json_path = Path(legacy_json_path) if legacy_json_path else None
        self._lock = threading.RLock()
        self._initialize()

    def list_chats(self) -> list[dict[str, Any]]:
        with self._lock:
            with self._connect() as connection:
                rows = connection.execute(
                    """
                    SELECT id, name, document_id, created_at, updated_at
                    FROM chats
                    ORDER BY updated_at DESC, created_at DESC
                    """
                ).fetchall()
                return [self._serialize_chat(connection, row) for row in rows]

    def get_chat(self, chat_id: str) -> dict[str, Any] | None:
        with self._lock:
            with self._connect() as connection:
                row = self._get_chat_row(connection, chat_id)
                if row is None:
                    return None
                return self._serialize_chat(connection, row)

    def create_or_replace_chat(
        self,
        *,
        chat_id: str,
        name: str,
        document_id: str,
        messages: list[dict[str, Any]],
    ) -> dict[str, Any]:
        with self._lock:
            with self._connect() as connection:
                existing = self._get_chat_row(connection, chat_id)
                now = _current_millis()
                created_at = existing["created_at"] if existing is not None else now
                normalized = self._normalize_chat_record(
                    {
                        "id": chat_id,
                        "name": name,
                        "documentId": document_id,
                        "messages": messages,
                        "createdAt": created_at,
                        "updatedAt": now,
                    }
                )
                self._write_chat(connection, normalized)
                row = self._get_chat_row(connection, chat_id)
                return self._serialize_chat(connection, row)

    def update_chat(
        self,
        *,
        chat_id: str,
        name: str | None = None,
        messages: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any] | None:
        with self._lock:
            with self._connect() as connection:
                existing = self._serialize_chat(connection, self._get_chat_row(connection, chat_id))
                if existing is None:
                    return None

                normalized = self._normalize_chat_record(
                    {
                        "id": chat_id,
                        "name": name if name is not None else existing["name"],
                        "documentId": existing["documentId"],
                        "messages": messages if messages is not None else existing["messages"],
                        "createdAt": existing["createdAt"],
                        "updatedAt": _current_millis(),
                    }
                )
                self._write_chat(connection, normalized)
                row = self._get_chat_row(connection, chat_id)
                return self._serialize_chat(connection, row)

    def delete_chat(self, chat_id: str) -> bool:
        with self._lock:
            with self._connect() as connection:
                cursor = connection.execute("DELETE FROM chats WHERE id = ?", (chat_id,))
                return cursor.rowcount > 0

    def delete_chats_for_document(self, document_id: str) -> int:
        with self._lock:
            with self._connect() as connection:
                cursor = connection.execute("DELETE FROM chats WHERE document_id = ?", (document_id,))
                return cursor.rowcount

    def delete_all_chats(self) -> None:
        with self._lock:
            with self._connect() as connection:
                connection.execute("DELETE FROM chats")

    def _initialize(self) -> None:
        with self._lock:
            self.database_path.parent.mkdir(parents=True, exist_ok=True)
            with self._connect() as connection:
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS chats (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        document_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """
                )
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        chat_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        message_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        status TEXT,
                        payload_json TEXT NOT NULL,
                        PRIMARY KEY (chat_id, position),
                        FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
                    )
                    """
                )
                connection.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_chats_document_updated
                    ON chats(document_id, updated_at DESC)
                    """
                )
                connection.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_chat_messages_chat_sequence
                    ON chat_messages(chat_id, position)
                    """
                )
                connection.execute("PRAGMA user_version = 1")

            self._migrate_legacy_json_if_needed()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA journal_mode = WAL")
        connection.execute("PRAGMA synchronous = NORMAL")
        connection.execute("PRAGMA busy_timeout = 5000")
        return connection

    def _migrate_legacy_json_if_needed(self) -> None:
        legacy_path = self.legacy_json_path
        if legacy_path is None or not legacy_path.exists():
            return

        with self._lock:
            with self._connect() as connection:
                row = connection.execute("SELECT COUNT(*) AS count FROM chats").fetchone()
                if row is not None and row["count"] > 0:
                    return

                try:
                    payload = json.loads(legacy_path.read_text(encoding="utf-8"))
                except Exception as exc:  # noqa: BLE001
                    logger.warning("Failed to parse legacy chat file %s: %s", legacy_path, exc)
                    return

                if not isinstance(payload, list) or not payload:
                    return

                for raw_chat in payload:
                    if not isinstance(raw_chat, dict):
                        continue
                    normalized = self._normalize_chat_record(raw_chat)
                    self._write_chat(connection, normalized)

                backup_path = self._next_legacy_backup_path(legacy_path)
                legacy_path.rename(backup_path)
                logger.info(
                    "Migrated %s legacy chats into SQLite and moved backup to %s",
                    len(payload),
                    backup_path,
                )

    def _next_legacy_backup_path(self, legacy_path: Path) -> Path:
        candidate = legacy_path.with_suffix(f"{legacy_path.suffix}.migrated")
        counter = 1
        while candidate.exists():
            candidate = legacy_path.with_suffix(f"{legacy_path.suffix}.migrated.{counter}")
            counter += 1
        return candidate

    def _get_chat_row(self, connection: sqlite3.Connection, chat_id: str) -> sqlite3.Row | None:
        return connection.execute(
            """
            SELECT id, name, document_id, created_at, updated_at
            FROM chats
            WHERE id = ?
            """,
            (chat_id,),
        ).fetchone()

    def _serialize_chat(self, connection: sqlite3.Connection, row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None

        message_rows = connection.execute(
            """
            SELECT payload_json
            FROM chat_messages
            WHERE chat_id = ?
            ORDER BY position ASC
            """,
            (row["id"],),
        ).fetchall()

        messages: list[dict[str, Any]] = []
        for message_row in message_rows:
            try:
                payload = json.loads(message_row["payload_json"])
            except json.JSONDecodeError:
                continue
            if isinstance(payload, dict):
                messages.append(payload)

        return {
            "id": row["id"],
            "name": row["name"],
            "documentId": row["document_id"],
            "messages": messages,
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
        }

    def _write_chat(self, connection: sqlite3.Connection, chat: dict[str, Any]) -> None:
        connection.execute(
            """
            INSERT INTO chats (id, name, document_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                document_id = excluded.document_id,
                updated_at = excluded.updated_at
            """,
            (
                chat["id"],
                chat["name"],
                chat["documentId"],
                chat["createdAt"],
                chat["updatedAt"],
            ),
        )
        connection.execute("DELETE FROM chat_messages WHERE chat_id = ?", (chat["id"],))
        for position, message in enumerate(chat["messages"]):
            connection.execute(
                """
                INSERT INTO chat_messages (
                    chat_id,
                    position,
                    message_id,
                    role,
                    content,
                    created_at,
                    status,
                    payload_json
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    chat["id"],
                    position,
                    message["id"],
                    message["role"],
                    message["content"],
                    message["createdAt"],
                    message.get("status"),
                    json.dumps(message, ensure_ascii=False),
                ),
            )

    def _normalize_chat_record(self, raw_chat: dict[str, Any]) -> dict[str, Any]:
        now = _current_millis()
        chat_id = _as_str(raw_chat.get("id"), "").strip() or f"chat_{now}"
        created_at = _as_int(raw_chat.get("createdAt"), now)
        updated_at = _as_int(raw_chat.get("updatedAt"), max(created_at, now))
        raw_messages = raw_chat.get("messages") if isinstance(raw_chat.get("messages"), list) else []

        messages: list[dict[str, Any]] = []
        for position, raw_message in enumerate(raw_messages):
            if not isinstance(raw_message, dict):
                continue
            message_created_at = _as_int(raw_message.get("createdAt"), created_at + position)
            normalized_message = dict(raw_message)
            normalized_message["id"] = _as_str(raw_message.get("id"), f"{chat_id}-msg-{position}")
            normalized_message["role"] = _as_str(raw_message.get("role"), "assistant")
            normalized_message["content"] = _as_str(raw_message.get("content"), "")
            normalized_message["createdAt"] = message_created_at
            status = raw_message.get("status")
            normalized_message["status"] = status if isinstance(status, str) else "sent"
            messages.append(normalized_message)

        return {
            "id": chat_id,
            "name": _as_str(raw_chat.get("name"), "Untitled chat"),
            "documentId": _as_str(raw_chat.get("documentId"), ""),
            "messages": messages,
            "createdAt": created_at,
            "updatedAt": updated_at,
        }