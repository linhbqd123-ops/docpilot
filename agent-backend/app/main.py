"""Agent Backend - FastAPI application entry point."""

from __future__ import annotations

import asyncio
import logging
import sys
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

# Load environment variables from .env file
load_dotenv()

# Add llm-layer to Python path so we can import llm_core
sys.path.insert(0, str(Path(__file__).parent.parent.parent / "llm-layer"))

# Use relative imports for local agent-backend modules to avoid colliding
# with the top-level `app` package in doc-mcp-service.
from .api.routes import router
from .agent.agent import DocPilotAgent
from .tools.document_tools import DocMCPClient
from .core.config import settings
from .core.logging_config import setup_backend_logging
from .persistence.database import db_service
from .persistence.event_bus import event_bus

from llm_core.core.config import load_llm_config
from llm_core.core.router import LLMRouter

setup_backend_logging()
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan: initialize and cleanup resources."""
    # Load LLM config and initialize agent
    config_path = settings.llm_config_path or str(
        Path(__file__).parent.parent.parent / "config" / "llm_config.json"
    )
    llm_config = load_llm_config(config_path)
    llm_router = LLMRouter(llm_config)
    app.state.llm_config_path = config_path
    app.state.llm_config_mtime = Path(config_path).stat().st_mtime if Path(config_path).exists() else None

    mcp_client = DocMCPClient(settings.doc_mcp_url)
    agent = DocPilotAgent(llm_router=llm_router, mcp_client=mcp_client)

    app.state.agent = agent
    logger.info(
        "Agent initialized with %d LLM provider(s), DOC-MCP at %s",
        len(llm_config.providers),
        settings.doc_mcp_url,
    )
    logger.info(
        "LLM config loaded from %s with providers: %s",
        config_path,
        [f"{p.name}:{p.model}" for p in llm_config.providers],
    )

    # Initialize persistence layer
    await db_service.init()
    app.state.db = db_service
    logger.info("Database service initialized at .data/docpilot.db")

    app.state.event_bus = event_bus

    # Start event bus in background
    event_bus_task = asyncio.create_task(event_bus.start())
    logger.info("Event bus started")

    yield

    # Cleanup
    await event_bus.stop()
    await event_bus_task
    logger.info("Event bus stopped")

    await db_service.close()
    logger.info("Database service closed")

    await llm_router.close()
    await mcp_client.close()
    logger.info("Agent shutdown complete")


def create_app() -> FastAPI:
    app = FastAPI(
        title=settings.app_name,
        version=settings.version,
        description="DocPilot AI Agent Backend",
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(router)

    @app.middleware("http")
    async def request_trace_middleware(request, call_next):
        request_id = str(uuid.uuid4())[:8]
        start = time.perf_counter()
        logger.info(
            "[REQ:%s] %s %s started",
            request_id,
            request.method,
            request.url.path,
        )
        try:
            response = await call_next(request)
            duration_ms = (time.perf_counter() - start) * 1000
            logger.info(
                "[REQ:%s] %s %s completed status=%s duration_ms=%.2f",
                request_id,
                request.method,
                request.url.path,
                response.status_code,
                duration_ms,
            )
            response.headers["X-Request-ID"] = request_id
            return response
        except Exception:
            duration_ms = (time.perf_counter() - start) * 1000
            logger.exception(
                "[REQ:%s] %s %s failed duration_ms=%.2f",
                request_id,
                request.method,
                request.url.path,
                duration_ms,
            )
            raise

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=True)
