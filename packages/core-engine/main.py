import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from starlette.requests import Request

from api.agent import router as agent_router
from api.chats import get_chat_store, router as chats_router
from api.debug import router as debug_router
from api.documents import get_document_store, router as documents_router
from api.health import router as health_router
from api.keys import router as keys_router
from debug_logging import (
    get_trace_id,
    log_core_event,
    reset_trace_id,
    sanitize_headers,
    serialize_http_body,
    set_trace_id,
)

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    logger.info("Initializing SQLite stores")
    get_chat_store()
    get_document_store()
    yield


app = FastAPI(
    title="DocPilot Core Engine",
    description="AI agent backend for DocPilot document editing",
    version="0.1.0",
    docs_url="/docs",
    redoc_url="/redoc",
    lifespan=lifespan,
)

# ---------------------------------------------------------------------------
# CORS — allow local dev origins + Tauri shell
# ---------------------------------------------------------------------------

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:5173",   # Vite dev server
        "http://localhost:4173",   # Vite preview
        "http://localhost:3000",
        "tauri://localhost",       # Tauri production
        "https://tauri.localhost",
    ],
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["*"],
    allow_credentials=True,
)

# ---------------------------------------------------------------------------
# Routers
# ---------------------------------------------------------------------------

app.include_router(health_router)
app.include_router(keys_router)
app.include_router(debug_router)
app.include_router(chats_router, prefix="/api")
app.include_router(agent_router, prefix="/api")
app.include_router(documents_router, prefix="/api")


def _should_capture_request_body(content_type: str) -> bool:
    return "multipart/form-data" not in content_type.lower()


@app.middleware("http")
async def trace_logging_middleware(request: Request, call_next):
    trace_token = set_trace_id(request.headers.get("X-DocPilot-Trace-Id"))
    request_body = b""
    content_type = request.headers.get("content-type", "")
    should_log_request = request.url.path != "/api/debug/frontend"

    if _should_capture_request_body(content_type):
        request_body = await request.body()

        async def receive() -> dict[str, object]:
            return {
                "type": "http.request",
                "body": request_body,
                "more_body": False,
            }

        request = Request(request.scope, receive)

    if should_log_request:
        log_core_event(
            "http.request",
            traceId=get_trace_id() or None,
            method=request.method,
            path=request.url.path,
            query=request.url.query,
            headers=sanitize_headers(dict(request.headers.items())),
            body=serialize_http_body(request_body, content_type),
        )

    try:
        response = await call_next(request)
    except Exception as exc:
        log_core_event(
            "http.error",
            traceId=get_trace_id() or None,
            method=request.method,
            path=request.url.path,
            error=str(exc),
        )
        reset_trace_id(trace_token)
        raise

    response.headers["X-DocPilot-Trace-Id"] = get_trace_id()
    if should_log_request:
        response_body = getattr(response, "body", b"")
        log_core_event(
            "http.response",
            traceId=get_trace_id() or None,
            method=request.method,
            path=request.url.path,
            statusCode=response.status_code,
            headers=sanitize_headers(dict(response.headers.items())),
            body=serialize_http_body(response_body if isinstance(response_body, (bytes, bytearray)) else b"", response.headers.get("content-type", "")),
        )

    reset_trace_id(trace_token)
    return response

# ---------------------------------------------------------------------------
# Entry point (python main.py)
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
