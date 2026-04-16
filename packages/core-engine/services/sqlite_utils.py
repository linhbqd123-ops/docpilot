from __future__ import annotations

import logging
import sqlite3
import threading
import time
from contextlib import contextmanager, suppress
from pathlib import Path
from typing import Callable, Iterator

logger = logging.getLogger(__name__)

SQLITE_BUSY_TIMEOUT_MS = 5000
SQLITE_CONNECT_TIMEOUT_SECONDS = SQLITE_BUSY_TIMEOUT_MS / 1000
SQLITE_INIT_RETRIES = 5
SQLITE_INIT_RETRY_DELAY_SECONDS = 0.2

_DATABASE_LOCKS: dict[str, threading.RLock] = {}
_DATABASE_LOCKS_GUARD = threading.Lock()


def prepare_sqlite_path(database_path: str | Path) -> Path:
    path = Path(database_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        for sidecar in _sqlite_sidecars(path):
            with suppress(FileNotFoundError, PermissionError):
                sidecar.unlink()
    return path


def connect_sqlite(database_path: str | Path) -> sqlite3.Connection:
    path = prepare_sqlite_path(database_path)
    connection = sqlite3.connect(
        path,
        timeout=SQLITE_CONNECT_TIMEOUT_SECONDS,
        check_same_thread=False,
    )
    connection.row_factory = sqlite3.Row
    connection.execute(f"PRAGMA busy_timeout = {SQLITE_BUSY_TIMEOUT_MS}")
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("PRAGMA synchronous = NORMAL")
    return connection


@contextmanager
def sqlite_connection(database_path: str | Path) -> Iterator[sqlite3.Connection]:
    connection = connect_sqlite(database_path)
    try:
        yield connection
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def initialize_sqlite(database_path: str | Path, initializer: Callable[[sqlite3.Connection], None]) -> None:
    path = prepare_sqlite_path(database_path)
    database_lock = _database_lock(path)

    with database_lock:
        for attempt in range(1, SQLITE_INIT_RETRIES + 1):
            try:
                with sqlite_connection(path) as connection:
                    _ensure_wal_mode(connection, path)
                    initializer(connection)
                return
            except sqlite3.OperationalError as exc:
                if not _is_locked_error(exc) or attempt == SQLITE_INIT_RETRIES:
                    raise
                logger.warning(
                    "SQLite init for %s is locked; retrying (%s/%s)",
                    path,
                    attempt,
                    SQLITE_INIT_RETRIES,
                )
                time.sleep(SQLITE_INIT_RETRY_DELAY_SECONDS)


def _ensure_wal_mode(connection: sqlite3.Connection, database_path: Path) -> None:
    try:
        connection.execute("PRAGMA journal_mode = WAL").fetchone()
    except sqlite3.OperationalError as exc:
        if not _is_locked_error(exc):
            raise
        logger.warning(
            "Could not switch %s to WAL because the database is locked; continuing with the current journal mode.",
            database_path,
        )


def _database_lock(database_path: Path) -> threading.RLock:
    key = str(database_path.resolve(strict=False))
    with _DATABASE_LOCKS_GUARD:
        lock = _DATABASE_LOCKS.get(key)
        if lock is None:
            lock = threading.RLock()
            _DATABASE_LOCKS[key] = lock
        return lock


def _sqlite_sidecars(database_path: Path) -> tuple[Path, Path]:
    return (
        Path(f"{database_path}-wal"),
        Path(f"{database_path}-shm"),
    )


def _is_locked_error(exc: sqlite3.OperationalError) -> bool:
    return "database is locked" in str(exc).lower()