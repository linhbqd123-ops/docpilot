import asyncio
from typing import Any

from agents import turn_orchestrator


async def _collect_events(*args: Any, **kwargs: Any) -> list[tuple[str, Any]]:
    events: list[tuple[str, Any]] = []
    async for event in turn_orchestrator.stream_turn(*args, **kwargs):
        events.append(event)
    return events


class FakeProvider:
    def __init__(self, stream_outputs: list[list[str]]) -> None:
        self.stream_outputs = list(stream_outputs)
        self.stream_calls: list[list[dict[str, str]]] = []
        self.chat_calls = 0
        self.provider_name = "fake"
        self.provider_display_name = "Fake Provider"
        self.default_model = "fake-model"

    async def stream_chat(self, messages: list[dict], model: str | None = None):
        self.stream_calls.append(messages)
        chunks = self.stream_outputs.pop(0)
        for chunk in chunks:
            yield chunk

    async def chat(self, messages: list[dict], model: str | None = None) -> str:
        self.chat_calls += 1
        raise AssertionError("stream_turn should use stream_chat() instead of chat().")


class AskFlowDocMcpClient:
    instances: list["AskFlowDocMcpClient"] = []

    def __init__(self) -> None:
        self.tool_calls: list[tuple[str, dict[str, Any]]] = []
        self.analysis_calls: list[str] = []
        AskFlowDocMcpClient.instances.append(self)

    async def get_session_summary(self, session_id: str) -> dict[str, Any]:
        return {
            "session_id": session_id,
            "state": "READY",
            "current_revision_id": "rev-current",
            "section_count": 3,
        }

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        self.tool_calls.append((name, arguments))
        if name == "answer_about_document":
            return {
                "headings": [{"block_id": "heading-1", "text": "Experience", "level": 1}],
                "relevant_snippets": [],
            }
        raise AssertionError(f"Unexpected MCP tool call: {name}")

    async def get_analysis_html(self, session_id: str) -> str:
        self.analysis_calls.append(session_id)
        return '<article><h1 style="font-size:28pt">Bach Quang Linh</h1><p style="font-weight:700">7 năm kinh nghiệm phát triển Java, Spring Boot, Docker, Azure.</p></article>'


class PlanningFlowDocMcpClient:
    instances: list["PlanningFlowDocMcpClient"] = []

    def __init__(self) -> None:
        self.tool_calls: list[tuple[str, dict[str, Any]]] = []
        self.source_calls: list[str] = []
        PlanningFlowDocMcpClient.instances.append(self)

    async def get_session_summary(self, session_id: str) -> dict[str, Any]:
        return {
            "session_id": session_id,
            "state": "READY",
            "current_revision_id": "rev-current",
            "section_count": 2,
        }

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        self.tool_calls.append((name, arguments))
        if name == "answer_about_document":
            return {
                "headings": [{"block_id": "heading-1", "text": "Summary", "level": 1}],
                "relevant_snippets": [{"block_id": "p-1", "text": "Current introduction paragraph."}],
            }
        if name == "inspect_document":
            return {
                "metadata": {"section_count": 2},
                "outline": [{"block_id": "heading-1", "text": "Summary", "level": 1}],
            }
        if name == "locate_relevant_context":
            return {
                "match_count": 1,
                "matches": [{"block_id": "p-1", "text": "Current introduction paragraph."}],
            }
        if name == "get_context_window":
            return {
                "focus": {"block_id": "p-1", "text": "Current introduction paragraph."},
                "before": [{"block_id": "heading-1", "text": "Summary"}],
                "after": [],
            }
        raise AssertionError(f"Unexpected MCP tool call: {name}")

    async def get_source_html(self, session_id: str) -> str:
        self.source_calls.append(session_id)
        return '<article><h1 data-doc-node-id="heading-1">Summary</h1><p data-doc-node-id="p-1">Current introduction paragraph.</p></article>'


def test_stream_turn_streams_question_answers_and_uses_analysis_html_fallback(monkeypatch) -> None:
    AskFlowDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", AskFlowDocMcpClient)
    provider = FakeProvider([["Bản tóm tắt CV: ", "7 năm kinh nghiệm Java."]])

    events = asyncio.run(
        _collect_events(
            provider,
            prompt="Tóm tắt CV này đi",
            mode="ask",
            document_session_id="session-ask",
            base_revision_id=None,
            selection=None,
            workspace_context=None,
            history=[],
        )
    )

    assert provider.chat_calls == 0
    assert len(provider.stream_calls) == 1

    client = AskFlowDocMcpClient.instances[0]
    assert client.analysis_calls == ["session-ask"]
    assert [name for name, _arguments in client.tool_calls] == ["answer_about_document"]

    streamed_text = "".join(data for event_type, data in events if event_type == "assistant_delta")
    assert streamed_text == "Bản tóm tắt CV: 7 năm kinh nghiệm Java."

    assert any(
        event_type == "tool_started" and data["tool"] == "get_analysis_html"
        for event_type, data in events
    )
    assert any(
        event_type == "tool_started" and data["tool"] == "llm_inference" and data["phase"] == "compose_answer"
        for event_type, data in events
    )
    llm_finished = next(
        data for event_type, data in events if event_type == "tool_finished" and data["tool"] == "llm_inference"
    )
    assert llm_finished["requestIndex"] == 1
    assert llm_finished["providerDisplayName"] == "Fake Provider"
    assert llm_finished["model"] == "fake-model"
    assert llm_finished["estimatedTotalTokens"] > 0

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["message"] == streamed_text
    assert final_payload["resultType"] == "answer"
    assert final_payload["usage"]["requestCount"] == 1
    assert final_payload["usage"]["requests"][0]["phase"] == "compose_answer"

    user_prompt = provider.stream_calls[0][-1]["content"]
    assert "Document analysis HTML excerpt" in user_prompt
    assert "font-size:28pt" in user_prompt
    assert "7 năm kinh nghiệm phát triển Java" in user_prompt


def test_stream_turn_agent_mode_can_answer_without_leaving_planning_flow(monkeypatch) -> None:
    PlanningFlowDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", PlanningFlowDocMcpClient)
    provider = FakeProvider(
        [[
            '{"decision":"answer","assistant_message":"Không cần thay đổi nào.","summary":"","operations":[],"risks":[],"needs_review":false}'
        ]]
    )

    events = asyncio.run(
        _collect_events(
            provider,
            prompt="Tóm tắt CV này đi",
            mode="agent",
            document_session_id="session-plan",
            base_revision_id=None,
            selection=None,
            workspace_context=None,
            history=[],
        )
    )

    assert provider.chat_calls == 0
    assert len(provider.stream_calls) == 1

    client = PlanningFlowDocMcpClient.instances[0]
    assert [name for name, _arguments in client.tool_calls] == [
        "answer_about_document",
        "inspect_document",
        "locate_relevant_context",
        "get_context_window",
    ]
    assert client.source_calls == ["session-plan"]
    assert any(
        event_type == "tool_started" and data["tool"] == "llm_inference" and data["phase"] == "plan_revision"
        for event_type, data in events
    )
    llm_finished = next(
        data for event_type, data in events if event_type == "tool_finished" and data["tool"] == "llm_inference"
    )
    assert llm_finished["requestIndex"] == 1
    assert llm_finished["phase"] == "plan_revision"
    assert llm_finished["estimatedTotalTokens"] > 0

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["message"] == "Không cần thay đổi nào."
    assert final_payload["resultType"] == "answer"
    assert final_payload["usage"]["requestCount"] == 1
    assert final_payload["usage"]["requests"][0]["phase"] == "plan_revision"

    planning_prompt = provider.stream_calls[0][-1]["content"]
    assert "Document source HTML excerpt" in planning_prompt
    assert 'data-doc-node-id="p-1"' in planning_prompt


class EditResolutionDocMcpClient:
    instances: list["EditResolutionDocMcpClient"] = []

    def __init__(self) -> None:
        self.tool_calls: list[tuple[str, dict[str, Any]]] = []
        self.source_calls: list[str] = []
        EditResolutionDocMcpClient.instances.append(self)

    async def get_session_summary(self, session_id: str) -> dict[str, Any]:
        return {
            "session_id": session_id,
            "state": "READY",
            "current_revision_id": "rev-current",
            "section_count": 1,
        }

    async def review_pending_revision(self, session_id: str, revision_id: str) -> dict[str, Any]:
        return {
            "revision_id": revision_id,
            "status": "PENDING",
            "summary": "Rename the candidate in personal details",
            "author": "agent",
            "scope": "minor",
            "created_at": None,
            "operation_count": 1,
            "operations": [{"op": "REPLACE_TEXT_RANGE", "description": "Rename the candidate", "block_id": "p-17"}],
        }

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        self.tool_calls.append((name, arguments))
        if name == "answer_about_document":
            return {
                "headings": [{"block_id": "heading-1", "text": "PERSONAL DETAILS", "level": 1}],
                "relevant_snippets": [{"block_id": "p-17", "text": "Name: Bach Quang Linh"}],
            }
        if name == "inspect_document":
            return {
                "metadata": {"section_count": 1},
                "outline": [{"block_id": "heading-1", "text": "PERSONAL DETAILS", "level": 1}],
            }
        if name == "locate_relevant_context":
            query = arguments["query"]
            if query == "sửa Name: Bach Quang Linh trong PERSONAL DETAILS thành Bach Quang Link":
                return {"match_count": 0, "matches": []}
            if query == "Name: Bach Quang Linh":
                return {
                    "match_count": 1,
                    "matches": [{"block_id": "p-17", "text": "Name: Bach Quang Linh", "logical_path": "/body/p[3]"}],
                }
            if query == "Bach Quang Linh":
                return {
                    "match_count": 1,
                    "matches": [{"block_id": "p-17", "text": "Name: Bach Quang Linh", "logical_path": "/body/p[3]"}],
                }
            if query == "PERSONAL DETAILS":
                return {
                    "match_count": 1,
                    "matches": [{"block_id": "heading-1", "text": "PERSONAL DETAILS", "logical_path": "/body/h1[1]"}],
                }
            raise AssertionError(f"Unexpected locate query: {query}")
        if name == "get_context_window":
            if arguments["block_id"] == "p-17":
                return {
                    "focus": {"block_id": "p-17", "text": "Name: Bach Quang Linh"},
                    "before": [{"block_id": "heading-1", "text": "PERSONAL DETAILS"}],
                    "after": [{"block_id": "p-18", "text": "Email: bach@example.com"}],
                }
            if arguments["block_id"] == "heading-1":
                return {
                    "focus": {"block_id": "heading-1", "text": "PERSONAL DETAILS"},
                    "before": [],
                    "after": [{"block_id": "p-17", "text": "Name: Bach Quang Linh"}],
                }
            raise AssertionError(f"Unexpected block id: {arguments['block_id']}")
        if name == "propose_document_edit":
            return {
                "revision_id": "rev-pending",
                "status": "PENDING",
                "summary": arguments["summary"],
                "operation_count": len(arguments["operations"]),
                "validation": {"structure_ok": True, "style_ok": True, "scope": "minor", "errors": [], "warnings": []},
            }
        raise AssertionError(f"Unexpected MCP tool call: {name}")

    async def get_source_html(self, session_id: str) -> str:
        self.source_calls.append(session_id)
        return (
            '<article><h1 data-doc-node-id="heading-1">PERSONAL DETAILS</h1>'
            '<p data-doc-node-id="p-17">Name: Bach Quang Linh</p>'
            '<p data-doc-node-id="p-18">Email: bach@example.com</p></article>'
        )


class InitialEditDocMcpClient(EditResolutionDocMcpClient):
    instances: list["InitialEditDocMcpClient"] = []

    def __init__(self) -> None:
        super().__init__()
        InitialEditDocMcpClient.instances.append(self)

    async def get_session_summary(self, session_id: str) -> dict[str, Any]:
        return {
            "session_id": session_id,
            "state": "READY",
            "current_revision_id": "",
            "section_count": 1,
        }

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        if name == "propose_document_edit":
            self.tool_calls.append((name, arguments))
            return {
                "revision_id": "rev-initial",
                "status": "PENDING",
                "summary": arguments["summary"],
                "operation_count": len(arguments["operations"]),
                "validation": {"structure_ok": True, "style_ok": True, "scope": "minor", "errors": [], "warnings": []},
            }
        return await super().call_tool(name, arguments)


def test_stream_turn_resolves_edit_targets_before_asking_for_block_ids(monkeypatch) -> None:
    EditResolutionDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", EditResolutionDocMcpClient)
    provider = FakeProvider(
        [[
            '{"decision":"propose_edit","assistant_message":"Tôi sẽ thay Name: Bach Quang Linh thành Bach Quang Link trong PERSONAL DETAILS và stage bản sửa để bạn review.","summary":"Rename the candidate in personal details","operations":[{"op":"REPLACE_TEXT_RANGE","target":{"block_id":"p-17","start":6,"end":21},"value":{"text":"Bach Quang Link"},"description":"Rename the candidate"}],"risks":[],"needs_review":true}'
        ]]
    )

    events = asyncio.run(
        _collect_events(
            provider,
            prompt="sửa Name: Bach Quang Linh trong PERSONAL DETAILS thành Bach Quang Link",
            mode="agent",
            document_session_id="session-edit",
            base_revision_id=None,
            selection=None,
            workspace_context=None,
            history=[],
        )
    )

    client = EditResolutionDocMcpClient.instances[0]
    locate_queries = [arguments["query"] for name, arguments in client.tool_calls if name == "locate_relevant_context"]
    assert locate_queries == [
        "sửa Name: Bach Quang Linh trong PERSONAL DETAILS thành Bach Quang Link",
        "Name: Bach Quang Linh",
        "Bach Quang Linh",
        "PERSONAL DETAILS",
    ]
    assert client.source_calls == ["session-edit"]

    planning_prompt = provider.stream_calls[0][-1]["content"]
    assert "Document source HTML excerpt" in planning_prompt
    assert "Resolved edit targets" in planning_prompt
    assert "p-17" in planning_prompt
    assert "PERSONAL DETAILS" in planning_prompt

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["resultType"] == "revision_staged"
    assert final_payload["revisionId"] == "rev-pending"
    assert final_payload["message"].startswith("Tôi sẽ thay Name: Bach Quang Linh")
    assert final_payload["usage"]["requestCount"] == 1
    assert final_payload["usage"]["estimatedTotalTokens"] > 0


def test_stream_turn_omits_empty_base_revision_id_for_initial_revision(monkeypatch) -> None:
    InitialEditDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", InitialEditDocMcpClient)
    provider = FakeProvider(
        [[
            '{"decision":"propose_edit","assistant_message":"Tôi sẽ stage bản sửa đầu tiên để bạn review.","summary":"Stage the first revision","operations":[{"op":"REPLACE_TEXT_RANGE","target":{"block_id":"p-17","start":6,"end":21},"value":{"text":"Bach Quang Link"},"description":"Rename the candidate"}],"risks":[],"needs_review":true}'
        ]]
    )

    events = asyncio.run(
        _collect_events(
            provider,
            prompt="sửa Name: Bach Quang Linh trong PERSONAL DETAILS thành Bach Quang Link",
            mode="agent",
            document_session_id="session-initial-edit",
            base_revision_id=None,
            selection=None,
            workspace_context=None,
            history=[],
        )
    )

    client = InitialEditDocMcpClient.instances[0]
    propose_arguments = next(arguments for name, arguments in client.tool_calls if name == "propose_document_edit")

    assert "base_revision_id" not in propose_arguments

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["baseRevisionId"] is None
    assert final_payload["revisionId"] == "rev-initial"