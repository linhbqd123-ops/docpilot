import json
import uuid
from dataclasses import dataclass
from typing import Any

import httpx

from config import settings
from debug_logging import get_trace_id, log_core_event, sanitize_headers


class DocMcpError(Exception):
    pass


class DocMcpUnavailableError(DocMcpError):
    pass


class DocMcpResponseError(DocMcpError):
    def __init__(
        self,
        message: str,
        status_code: int,
        *,
        code: str | None = None,
        items: list[str] | None = None,
        retryable: bool | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.items = items or []
        self.retryable = retryable


@dataclass(frozen=True)
class MappedDocMcpError:
    status_code: int
    message: str
    code: str | None = None
    items: tuple[str, ...] = ()


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _first_non_empty(*values: Any) -> str | None:
    for value in values:
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _as_string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item.strip() for item in value if isinstance(item, str) and item.strip()]


def _status_from_rpc_error_code(code: Any) -> int:
    if code == -32602:
        return 400
    if code in {-32601, -32004}:
        return 404
    if code == -32009:
        return 409
    return 502


def _extract_error_details(payload: Any) -> tuple[str | None, str | None, list[str], int | None, bool | None]:
    record = _as_dict(payload)
    data = _as_dict(record.get("data"))
    message = _first_non_empty(
        data.get("user_message"),
        record.get("detail"),
        record.get("error"),
        record.get("message"),
    )
    code = _first_non_empty(data.get("code"), record.get("code"))
    items = _as_string_list(data.get("items") or record.get("items"))

    http_status_raw = data.get("http_status") if data else record.get("status")
    http_status = http_status_raw if isinstance(http_status_raw, int) else None
    retryable_raw = data.get("retryable") if data else None
    retryable = retryable_raw if isinstance(retryable_raw, bool) else None
    return message, code, items, http_status, retryable


def map_doc_mcp_error(
    exc: Exception,
    *,
    internal_message: str = "The document request could not be completed right now. Please try again.",
) -> MappedDocMcpError:
    if isinstance(exc, DocMcpUnavailableError):
        return MappedDocMcpError(
            status_code=503,
            message="The document service is unavailable right now. Please try again shortly.",
            code="doc_mcp_unavailable",
        )
    if isinstance(exc, DocMcpResponseError):
        status_code = exc.status_code if exc.status_code in {400, 404, 409, 422, 502, 503} else 502
        return MappedDocMcpError(
            status_code=status_code,
            message=str(exc),
            code=exc.code,
            items=tuple(exc.items),
        )
    return MappedDocMcpError(status_code=500, message=internal_message, code="internal_error")


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

    async def get_source_html(self, session_id: str) -> str:
        return await self._request(
            "GET",
            f"/api/sessions/{session_id}/source/html",
            expected="text",
        )

    async def get_analysis_html(self, session_id: str) -> str:
        return await self._request(
            "GET",
            f"/api/sessions/{session_id}/analysis/html",
            expected="text",
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
            error_payload = _as_dict(response["error"])
            message, error_code, items, http_status, retryable = _extract_error_details(error_payload)
            rpc_code = error_payload.get("code", 500)
            raise DocMcpResponseError(
                message or "The document action could not be completed right now. Please try again.",
                http_status or _status_from_rpc_error_code(rpc_code),
                code=error_code,
                items=items,
                retryable=retryable,
            )

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
        request_kwargs = dict(kwargs)
        headers = dict(request_kwargs.pop("headers", {}) or {})
        trace_id = get_trace_id()
        if trace_id:
            headers.setdefault("X-DocPilot-Trace-Id", trace_id)
        request_kwargs["headers"] = headers

        request_payload: Any = None
        if "json" in request_kwargs:
            request_payload = request_kwargs["json"]
        elif "params" in request_kwargs:
            request_payload = request_kwargs["params"]
        elif "files" in request_kwargs:
            request_payload = {
                "files": {
                    name: {
                        "filename": value[0],
                        "contentType": value[2] if len(value) > 2 else None,
                        "length": len(value[1]) if isinstance(value[1], (bytes, bytearray)) else None,
                    }
                    for name, value in request_kwargs["files"].items()
                }
            }

        log_core_event(
            "doc_mcp.request",
            method=method,
            url=f"{self.base_url}{path}",
            expected=expected,
            headers=sanitize_headers(headers),
            payload=request_payload,
        )

        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.request(method, f"{self.base_url}{path}", **request_kwargs)
        except httpx.ConnectError as exc:
            log_core_event(
                "doc_mcp.error",
                method=method,
                url=f"{self.base_url}{path}",
                error=str(exc),
            )
            raise DocMcpUnavailableError(
                f"Doc-mcp service is unavailable at {self.base_url}."
            ) from exc
        except httpx.HTTPError as exc:
            log_core_event(
                "doc_mcp.error",
                method=method,
                url=f"{self.base_url}{path}",
                error=str(exc),
            )
            raise DocMcpError(f"Doc-mcp request failed: {exc}") from exc

        response_payload: Any
        if expected == "bytes":
            response_payload = {
                "type": "bytes",
                "length": len(response.content),
            }
        elif expected == "text":
            response_payload = response.text
        else:
            try:
                response_payload = response.json()
            except json.JSONDecodeError:
                response_payload = response.text

        log_core_event(
            "doc_mcp.response",
            method=method,
            url=f"{self.base_url}{path}",
            statusCode=response.status_code,
            contentType=response.headers.get("content-type", ""),
            body=response_payload,
        )

        if not response.is_success:
            message, error_code, items, _http_status, retryable = _extract_error_details(response_payload)
            raise DocMcpResponseError(
                message or f"The document service returned an error ({response.status_code}).",
                response.status_code,
                code=error_code,
                items=items,
                retryable=retryable,
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