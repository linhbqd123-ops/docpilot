"""Agent tools that interface with the DOC-MCP service.

Provides an async HTTP client for calling DOC-MCP REST endpoints.
"""

from __future__ import annotations

import logging
from typing import Optional

import httpx

from app.core.config import settings

logger = logging.getLogger(__name__)


class ToolError(Exception):
    pass


class DocMCPClient:
    """HTTP client for the DOC-MCP service."""

    def __init__(self, base_url: str | None = None):
        self.base_url = (base_url or settings.doc_mcp_url).rstrip("/")
        self._client = httpx.AsyncClient(
            base_url=self.base_url,
            timeout=httpx.Timeout(60.0, connect=10.0),
        )

    async def close(self):
        await self._client.aclose()

    async def _post(self, endpoint: str, payload: dict) -> dict:
        try:
            response = await self._client.post(endpoint, json=payload)
            response.raise_for_status()
            return response.json()
        except httpx.HTTPStatusError as e:
            raise ToolError(f"DOC-MCP error {e.response.status_code}: {e.response.text}")
        except httpx.RequestError as e:
            raise ToolError(f"DOC-MCP connection error: {e}")

    async def extract_structure(
        self, document_base64: Optional[str] = None, include_formatting: bool = True
    ) -> dict:
        return await self._post("/extract-structure", {
            "document_base64": document_base64,
            "include_formatting": include_formatting,
        })

    async def apply_changes(
        self, changes: list[dict], document_base64: Optional[str] = None
    ) -> dict:
        return await self._post("/apply-changes", {
            "changes": changes,
            "document_base64": document_base64,
        })

    async def clear_document(self, document_base64: Optional[str] = None) -> dict:
        return await self._post("/clear-document", {
            "document_base64": document_base64,
        })

    async def insert_structured_content(
        self,
        blocks: list[dict],
        position: str = "end",
        document_base64: Optional[str] = None,
    ) -> dict:
        return await self._post("/insert-structured-content", {
            "blocks": blocks,
            "position": position,
            "document_base64": document_base64,
        })
