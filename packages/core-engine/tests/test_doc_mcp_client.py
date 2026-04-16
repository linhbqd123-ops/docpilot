import asyncio

import pytest

from services.doc_mcp_client import DocMcpClient, DocMcpResponseError, DocMcpUnavailableError, map_doc_mcp_error


def test_map_doc_mcp_error_uses_safe_service_message() -> None:
    mapped = map_doc_mcp_error(
        DocMcpUnavailableError("Connection refused"),
        internal_message="fallback",
    )

    assert mapped.status_code == 503
    assert mapped.code == "doc_mcp_unavailable"
    assert mapped.message == "The document service is unavailable right now. Please try again shortly."


def test_map_doc_mcp_error_preserves_user_safe_details() -> None:
    mapped = map_doc_mcp_error(
        DocMcpResponseError(
            "Could not stage the document edit right now. Please try again.",
            502,
            code="document_action_failed",
            items=["Try again in a moment."],
        ),
        internal_message="fallback",
    )

    assert mapped.status_code == 502
    assert mapped.code == "document_action_failed"
    assert mapped.message == "Could not stage the document edit right now. Please try again."
    assert mapped.items == ("Try again in a moment.",)


def test_call_tool_prefers_structured_user_message(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_request(self, method: str, path: str, *, expected: str = "json", **kwargs):
        return {
            "jsonrpc": "2.0",
            "id": "1",
            "error": {
                "code": -32603,
                "message": "Internal error",
                "data": {
                    "http_status": 502,
                    "code": "document_action_failed",
                    "user_message": "Could not stage the document edit right now. Please try again.",
                    "items": ["Try again in a moment."],
                    "retryable": True,
                },
            },
        }

    monkeypatch.setattr(DocMcpClient, "_request", fake_request)

    with pytest.raises(DocMcpResponseError) as exc_info:
        asyncio.run(DocMcpClient(base_url="http://example.test").call_tool("propose_document_edit", {}))

    exc = exc_info.value
    assert str(exc) == "Could not stage the document edit right now. Please try again."
    assert exc.status_code == 502
    assert exc.code == "document_action_failed"
    assert exc.items == ["Try again in a moment."]
    assert exc.retryable is True