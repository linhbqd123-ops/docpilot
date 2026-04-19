"""Provider Settings Store - per-provider model overrides and other settings."""

from __future__ import annotations

import sqlite3
import threading
from pathlib import Path
from typing import Any

from services.sqlite_utils import initialize_sqlite, sqlite_connection

import logging
logger = logging.getLogger(__name__)


class ProviderSettingsStore:
    """Store provider-specific settings (model_override, etc.) in SQLite."""

    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)
        self._lock = threading.RLock()
        self._initialize()

    def _initialize(self) -> None:
        """Initialize the provider_settings table if it doesn't exist."""
        def initializer(connection: sqlite3.Connection) -> None:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS provider_settings (
                    provider TEXT PRIMARY KEY,
                    model_override TEXT,
                    updated_at INTEGER
                )
                """
            )

        initialize_sqlite(self.database_path, initializer)

    def _connect(self) -> sqlite_connection:
        return sqlite_connection(self.database_path)

    def set_model_override(self, provider: str, model_override: str | None) -> None:
        """Set model override for a provider. Pass None to clear."""
        with self._lock:
            with self._connect() as connection:
                import time
                now_ms = int(time.time() * 1000)
                
                # Clear if empty/None
                if not model_override or not model_override.strip():
                    connection.execute(
                        "DELETE FROM provider_settings WHERE provider = ?",
                        (provider,)
                    )
                    logger.info(f"Cleared model_override for provider: {provider}")
                else:
                    # Upsert
                    connection.execute(
                        """
                        INSERT INTO provider_settings (provider, model_override, updated_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT(provider) DO UPDATE SET
                            model_override = excluded.model_override,
                            updated_at = excluded.updated_at
                        """,
                        (provider, model_override.strip(), now_ms)
                    )
                    logger.info(f"Set model_override for provider {provider}: {model_override}")

    def get_model_override(self, provider: str) -> str | None:
        """Get model override for a provider, or None if not set."""
        with self._lock:
            with self._connect() as connection:
                row = connection.execute(
                    "SELECT model_override FROM provider_settings WHERE provider = ?",
                    (provider,)
                ).fetchone()
                
                if row is None:
                    return None
                
                return row["model_override"] or None

    def list_all(self) -> dict[str, dict[str, Any]]:
        """List all provider settings."""
        with self._lock:
            with self._connect() as connection:
                rows = connection.execute(
                    "SELECT provider, model_override, updated_at FROM provider_settings"
                ).fetchall()
                
                result = {}
                for row in rows:
                    result[row["provider"]] = {
                        "model_override": row["model_override"],
                        "updated_at": row["updated_at"]
                    }
                
                return result
