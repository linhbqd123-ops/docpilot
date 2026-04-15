import json
import uuid
from typing import Any

import httpx

from config import settings


class DocMcpError(Exception):
    pass


class DocMcpUnavailableError(DocMcpError):
    pass


class DocMcpResponseError(DocMcpError):
    def __init__(self, message: str, status_code: int) -> None:
        super().__init__(message)
        self.status_code = status_code


class DocMcpClient:
    def __init__(self, base_url: str | None = None, timeout: int | None = None) -> None:
        self.base_url = (base_url or settings.doc_mcp_url).rstrip("/")
        self.timeout = timeout or settings.request_timeout_seconds

    async def import_docx_session(
        self,
        filename: str,
        content: bytes,
        content_type: str | None,
    ) -> dict[str, Any]:
        files = {
            "file": (
                filename,
                content,
                content_type or "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
        }
        data = await self._request("POST", "/api/sessions/import-docx", files=files)
        return self._as_json_dict(data)

    async def import_pdf_to_html(
        self,
        filename: str,
        content: bytes,
        content_type: str | None,
    ) -> dict[str, Any]:
        files = {
            "file": (
                filename,
                content,
                content_type or "application/pdf",
            )
        }
        data = await self._request("POST", "/api/imports/pdf-to-html", files=files)
        return self._as_json_dict(data)

    async def get_session(self, session_id: str) -> dict[str, Any]:
        data = await self._request("GET", f"/api/sessions/{session_id}")
        return self._as_json_dict(data)

    async def get_session_summary(self, session_id: str) -> dict[str, Any]:
        data = await self._request("GET", f"/api/sessions/{session_id}/summary")
        return self._as_json_dict(data)

    async def get_html_projection(self, session_id: str, fragment: bool = True) -> str:
        return await self._request(
            "GET",
            f"/api/sessions/{session_id}/projection/html",
            expected="text",
            params={"fragment": fragment},
        )

    async def list_session_revisions(
        self,
        session_id: str,
        status: str | None = None,
    ) -> list[dict[str, Any]]:
        params = {"status": status} if status else None
        data = await self._request("GET", f"/api/sessions/{session_id}/revisions", params=params)
        if not isinstance(data, list):
            raise DocMcpError("Expected a JSON array from doc-mcp revisions endpoint.")
        return [item for item in data if isinstance(item, dict)]

    async def export_session_docx(self, session_id: str) -> bytes:
        return await self._request(
            "POST",
            f"/api/sessions/{session_id}/export-docx",
            expected="bytes",
        )

    async def get_revision(self, revision_id: str) -> dict[str, Any]:
        data = await self._request("GET", f"/api/revisions/{revision_id}")
        return self._as_json_dict(data)

    async def apply_revision(self, revision_id: str) -> dict[str, Any]:
        data = await self._request("POST", f"/api/revisions/{revision_id}/apply")
        return self._as_json_dict(data)

    async def reject_revision(self, revision_id: str) -> dict[str, Any]:
        data = await self._request("POST", f"/api/revisions/{revision_id}/reject")
        return self._as_json_dict(data)

    async def rollback_revision(self, revision_id: str) -> dict[str, Any]:
        data = await self._request("POST", f"/api/revisions/{revision_id}/rollback")
        return self._as_json_dict(data)

    async def review_pending_revision(self, session_id: str, revision_id: str) -> dict[str, Any]:
        response = await self.call_tool(
            "review_pending_revision",
            {"session_id": session_id, "revision_id": revision_id},
        )
        return self._as_json_dict(response)

    async def compare_revisions(
        self,
        session_id: str,
        base_revision_id: str,
        target_revision_id: str,
    ) -> dict[str, Any]:
        response = await self.call_tool(
            "compare_revisions",
            {
                "session_id": session_id,
                "base_revision_id": base_revision_id,
                "target_revision_id": target_revision_id,
            },
        )
        return self._as_json_dict(response)

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        payload = {
            "jsonrpc": "2.0",
            "id": str(uuid.uuid4()),
            "method": "tools/call",
            "params": {
                "name": name,
                "arguments": arguments,
            },
        }
        response = await self._request("POST", "/mcp", json=payload)
        if not isinstance(response, dict):
            raise DocMcpError("Unexpected MCP response shape.")
        if "error" in response:
            message = response["error"].get("message", "Unknown MCP error")
            code = response["error"].get("code", 500)
            raise DocMcpResponseError(f"MCP tool '{name}' failed: {message}", int(code))

        content = response.get("result", {}).get("content", [])
        if not content:
            return None

        text = content[0].get("text", "")
        if not text:
            return None

        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return text

    async def _request(
        self,
        method: str,
        path: str,
        *,
        expected: str = "json",
        **kwargs: Any,
    ) -> Any:
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.request(method, f"{self.base_url}{path}", **kwargs)
        except httpx.ConnectError as exc:
            raise DocMcpUnavailableError(
                f"Doc-mcp service is unavailable at {self.base_url}."
            ) from exc
        except httpx.HTTPError as exc:
            raise DocMcpError(f"Doc-mcp request failed: {exc}") from exc

        if not response.is_success:
            raise DocMcpResponseError(
                f"Doc-mcp request failed ({response.status_code}): {response.text}",
                response.status_code,
            )

        if expected == "text":
            return response.text
        if expected == "bytes":
            return response.content
        return response.json()

    def _as_json_dict(self, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise DocMcpError("Expected a JSON object from doc-mcp.")
        return payload