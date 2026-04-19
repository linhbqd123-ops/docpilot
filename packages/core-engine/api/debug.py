from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from debug_logging import log_desktop_event, log_docmcp_event

router = APIRouter(prefix="/api/debug", tags=["debug"])

# Central logs folder
_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
_LOGS_ROOT = _PROJECT_ROOT / "logs"


class FrontendDebugEvent(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    trace_id: str = Field(alias="traceId")
    event: str
    payload: Any = None


class DocMcpDebugEvent(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    trace_id: str | None = Field(default=None, alias="traceId")
    event: str
    payload: Any = None


@router.post("/frontend")
async def capture_frontend_debug_event(body: FrontendDebugEvent, request: Request) -> dict[str, Any]:
    # Forward frontend debug events into centralized desktop logs
    log_desktop_event(
        body.event,
        trace_id=body.trace_id,
        payload=body.payload,
        source="desktop",
        userAgent=request.headers.get("user-agent", ""),
        clientHost=request.client.host if request.client else None,
    )
    return {"ok": True, "traceId": body.trace_id}


@router.post("/doc-mcp")
async def capture_docmcp_debug_event(body: DocMcpDebugEvent) -> dict[str, Any]:
    """Accept error and debug events from the Java doc-mcp service.
    
    Doc-mcp can send logs here via HTTP POST to ensure centralized error tracking.
    """
    log_docmcp_event(
        body.event,
        traceId=body.trace_id,
        payload=body.payload,
    )
    return {"ok": True, "traceId": body.trace_id}


@router.get("/logs")
async def list_debug_logs() -> dict[str, Any]:
    """List all available debug log files."""
    try:
        _LOGS_ROOT.mkdir(parents=True, exist_ok=True)
        files = []
        for log_file in sorted(_LOGS_ROOT.glob("*.jsonl")):
            stat = log_file.stat()
            files.append({
                "name": log_file.name,
                "size": stat.st_size,
                "lines": sum(1 for _ in log_file.open()),
            })
        return {
            "logsRoot": str(_LOGS_ROOT),
            "files": files,
            "totalFiles": len(files),
        }
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@router.get("/logs/{filename}")
async def get_debug_log(filename: str, limit: int = 100, offset: int = 0) -> dict[str, Any]:
    """Get last N lines from a debug log file (JSONL format).
    
    Args:
        filename: Name of the log file (e.g., 'core-engine-error.jsonl')
        limit: Number of lines to return (default: 100, max: 1000)
        offset: Offset from the end (0 = most recent)
    """
    limit = min(limit, 1000)  # Cap limit
    
    log_file = _LOGS_ROOT / filename
    if not log_file.exists():
        raise HTTPException(status_code=404, detail=f"Log file not found: {filename}")
    
    if not log_file.name.endswith(".jsonl"):
        raise HTTPException(status_code=400, detail="Only JSONL log files are supported")
    
    try:
        lines = []
        with log_file.open("r", encoding="utf-8") as f:
            all_lines = f.readlines()
            # Get last 'limit' lines, starting from offset
            start_idx = max(0, len(all_lines) - limit - offset)
            end_idx = max(0, len(all_lines) - offset)
            for line in all_lines[start_idx:end_idx]:
                if line.strip():
                    try:
                        lines.append(json.loads(line))
                    except json.JSONDecodeError:
                        lines.append({"rawLine": line.strip()})
        
        return {
            "file": filename,
            "total": len(all_lines),
            "returned": len(lines),
            "limit": limit,
            "offset": offset,
            "lines": lines,
        }
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@router.get("/logs/{filename}/errors")
async def get_error_summary(filename: str) -> dict[str, Any]:
    """Get a summary of errors in a log file, grouped by error type."""
    log_file = _LOGS_ROOT / filename
    if not log_file.exists():
        raise HTTPException(status_code=404, detail=f"Log file not found: {filename}")
    
    try:
        error_summary: dict[str, int] = {}
        total = 0
        
        with log_file.open("r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    try:
                        record = json.loads(line)
                        total += 1
                        
                        # Group by error type or event
                        error_key = record.get("exceptionType") or record.get("errorCode") or record.get("event", "unknown")
                        error_summary[error_key] = error_summary.get(error_key, 0) + 1
                    except json.JSONDecodeError:
                        pass
        
        return {
            "file": filename,
            "totalLines": total,
            "errorTypes": error_summary,
        }
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))
