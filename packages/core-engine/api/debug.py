from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field

from debug_logging import log_desktop_event

router = APIRouter(prefix="/api/debug", tags=["debug"])


class FrontendDebugEvent(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    trace_id: str = Field(alias="traceId")
    event: str
    payload: Any = None


@router.post("/frontend")
async def capture_frontend_debug_event(body: FrontendDebugEvent, request: Request) -> dict[str, Any]:
    log_desktop_event(
        body.event,
        trace_id=body.trace_id,
        payload=body.payload,
        source="desktop",
        userAgent=request.headers.get("user-agent", ""),
        clientHost=request.client.host if request.client else None,
    )
    return {"ok": True, "traceId": body.trace_id}
