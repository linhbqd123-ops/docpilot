from __future__ import annotations

import contextvars
import json
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


_trace_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("docpilot_trace_id", default="")

_CORE_ENGINE_ROOT = Path(__file__).resolve().parent
_PACKAGES_ROOT = _CORE_ENGINE_ROOT.parent
_CORE_ENGINE_DEBUG_DIR = _CORE_ENGINE_ROOT / "debug"
_DESKTOP_DEBUG_DIR = _PACKAGES_ROOT / "desktop" / "debug"


def new_trace_id() -> str:
    return str(uuid.uuid4())


def get_trace_id() -> str:
    return _trace_id_var.get()


def set_trace_id(trace_id: str | None) -> contextvars.Token[str]:
    value = (trace_id or "").strip() or new_trace_id()
    return _trace_id_var.set(value)


def reset_trace_id(token: contextvars.Token[str]) -> None:
    _trace_id_var.reset(token)


def _json_default(value: Any) -> Any:
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, bytes):
        return {
            "type": "bytes",
            "length": len(value),
        }
    if isinstance(value, set):
        return sorted(value)
    if hasattr(value, "model_dump"):
        return value.model_dump()
    if hasattr(value, "dict"):
        return value.dict()
    if hasattr(value, "isoformat"):
        try:
            return value.isoformat()
        except TypeError:
            pass
    return str(value)


class _JsonlWriter:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = threading.Lock()

    def write(self, event: str, payload: dict[str, Any], *, trace_id: str | None = None) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        record = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "traceId": trace_id or get_trace_id() or None,
            "event": event,
            **payload,
        }
        line = json.dumps(record, ensure_ascii=False, default=_json_default)
        with self._lock:
            with self.path.open("a", encoding="utf-8") as handle:
                handle.write(line + "\n")


_core_writer = _JsonlWriter(_CORE_ENGINE_DEBUG_DIR / "core-engine-flow.jsonl")
_desktop_writer = _JsonlWriter(_DESKTOP_DEBUG_DIR / "desktop-flow.jsonl")


def log_core_event(event: str, **payload: Any) -> None:
    _core_writer.write(event, payload)


def log_desktop_event(event: str, *, trace_id: str | None = None, **payload: Any) -> None:
    _desktop_writer.write(event, payload, trace_id=trace_id)


def sanitize_headers(headers: dict[str, str]) -> dict[str, str]:
    sanitized: dict[str, str] = {}
    for key, value in headers.items():
        lowered = key.lower()
        if lowered in {"authorization", "cookie", "set-cookie", "x-api-key", "api-key"}:
            sanitized[key] = "<redacted>"
        else:
            sanitized[key] = value
    return sanitized


def serialize_http_body(body: bytes, content_type: str | None) -> Any:
    if not body:
        return ""

    normalized_content_type = (content_type or "").lower()
    if "multipart/form-data" in normalized_content_type:
        return {
            "type": "multipart",
            "length": len(body),
        }

    if any(token in normalized_content_type for token in ("application/json", "text/", "application/xml", "application/x-www-form-urlencoded")):
        try:
            decoded = body.decode("utf-8")
        except UnicodeDecodeError:
            decoded = body.decode("utf-8", errors="replace")
        try:
            return json.loads(decoded)
        except json.JSONDecodeError:
            return decoded

    return {
        "type": "binary",
        "length": len(body),
        "contentType": content_type or "",
    }
