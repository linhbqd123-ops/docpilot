from __future__ import annotations

import contextvars
import inspect
import json
import threading
import traceback
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


_trace_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("docpilot_trace_id", default="")

_CORE_ENGINE_ROOT = Path(__file__).resolve().parent
_PACKAGES_ROOT = _CORE_ENGINE_ROOT.parent
# Central logs root (project)/logs
_PROJECT_ROOT = _PACKAGES_ROOT.parent
_LOGS_ROOT = _PROJECT_ROOT / "logs"

# legacy debug dirs (no longer used)
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
        # Ensure logs directory exists (only the central `logs/` folder)
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
        except Exception:
            # Never fail application startup due to logging directory creation
            pass

    def _write_record(self, record: dict[str, Any]) -> None:
        line = json.dumps(record, ensure_ascii=False, default=_json_default)
        with self._lock:
            with self.path.open("a", encoding="utf-8") as handle:
                handle.write(line + "\n")


def _get_caller_context() -> dict[str, Any]:
    for frame in inspect.stack()[2:10]:
        fname = Path(frame.filename).name
        if fname == Path(__file__).name:
            continue
        return {
            "func": frame.function,
            "file": fname,
            "line": frame.lineno,
        }
    return {"func": None, "file": None, "line": None}


_core_info_writer = _JsonlWriter(_LOGS_ROOT / "core-engine-info.jsonl")
_core_error_writer = _JsonlWriter(_LOGS_ROOT / "core-engine-error.jsonl")

_desktop_info_writer = _JsonlWriter(_LOGS_ROOT / "desktop-info.jsonl")
_desktop_error_writer = _JsonlWriter(_LOGS_ROOT / "desktop-error.jsonl")

_docmcp_info_writer = _JsonlWriter(_LOGS_ROOT / "doc-mcp-info.jsonl")
_docmcp_error_writer = _JsonlWriter(_LOGS_ROOT / "doc-mcp-error.jsonl")


def _is_error_event(event: str, payload: dict[str, Any]) -> bool:
    name = (event or "").lower()
    if "error" in name or name.endswith(".error"):
        return True
    if any(k in payload for k in ("exception", "exc", "error", "traceback")):
        return True
    return False


def _format_exception(exc: BaseException) -> str:
    try:
        return "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    except Exception:
        return str(exc)


def _emit(writer: _JsonlWriter, level: str, module: str, event: str, payload: dict[str, Any], *, trace_id: str | None = None) -> None:
    ctx = _get_caller_context()
    record: dict[str, Any] = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "level": level,
        "module": module,
        "event": event,
        "traceId": trace_id or get_trace_id() or None,
        "caller": ctx,
    }
    # Merge payload gently, converting non-serializable items using _json_default
    for k, v in payload.items():
        # Capture exception traceback if present
        if k in ("exception", "exc") and isinstance(v, BaseException):
            record["exceptionType"] = type(v).__name__
            record["exceptionMessage"] = str(v)
            record["traceback"] = _format_exception(v)
        else:
            record[k] = v

    writer._write_record(record)


def log_core_event(event: str, **payload: Any) -> None:
    """Write a structured info/error record for core-engine.

    Heuristically treats events containing 'error' or carrying an exception
    as errors (written to the error file). All other events go to the info file.
    """
    if _is_error_event(event, payload):
        _emit(_core_error_writer, "error", "core-engine", event, payload, trace_id=payload.get("traceId"))
    else:
        _emit(_core_info_writer, "info", "core-engine", event, payload, trace_id=payload.get("traceId"))


def log_desktop_event(event: str, *, trace_id: str | None = None, **payload: Any) -> None:
    """Write a structured record for desktop-originated events.

    Desktop info events go to `desktop-info.jsonl`. Errors are routed to
    `desktop-error.jsonl`.
    """
    if _is_error_event(event, payload):
        _emit(_desktop_error_writer, "error", "desktop", event, payload, trace_id=trace_id)
    else:
        _emit(_desktop_info_writer, "info", "desktop", event, payload, trace_id=trace_id)


def log_docmcp_event(event: str, **payload: Any) -> None:
    if _is_error_event(event, payload):
        _emit(_docmcp_error_writer, "error", "doc-mcp", event, payload, trace_id=payload.get("traceId"))
    else:
        _emit(_docmcp_info_writer, "info", "doc-mcp", event, payload, trace_id=payload.get("traceId"))


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
