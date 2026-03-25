"""Agent Backend - FastAPI application entry point."""

from __future__ import annotations

import asyncio
import logging
import sys
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

# Add llm-layer to Python path so we can import llm_core
sys.path.insert(0, str(Path(__file__).parent.parent.parent / "llm-layer"))

from app.api.routes import router
from app.agent.agent import DocPilotAgent
from app.tools.document_tools import DocMCPClient
from app.core.config import settings
from app.persistence.database import db_service
from app.persistence.event_bus import event_bus

from llm_core.core.config import load_llm_config
from llm_core.core.router import LLMRouter

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
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

    mcp_client = DocMCPClient(settings.doc_mcp_url)
    agent = DocPilotAgent(llm_router=llm_router, mcp_client=mcp_client)

    app.state.agent = agent
    logger.info(
        "Agent initialized with %d LLM provider(s), DOC-MCP at %s",
        len(llm_config.providers),
        settings.doc_mcp_url,
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
    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=True)
