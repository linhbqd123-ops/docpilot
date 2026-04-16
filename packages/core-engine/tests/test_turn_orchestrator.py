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
        self.projection_calls: list[tuple[str, bool]] = []
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

    async def get_html_projection(self, session_id: str, fragment: bool = True) -> str:
        self.projection_calls.append((session_id, fragment))
        return "<article><h1>Bach Quang Linh</h1><p>7 năm kinh nghiệm phát triển Java, Spring Boot, Docker, Azure.</p></article>"


class PlanningFlowDocMcpClient:
    instances: list["PlanningFlowDocMcpClient"] = []

    def __init__(self) -> None:
        self.tool_calls: list[tuple[str, dict[str, Any]]] = []
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
        raise AssertionError(f"Unexpected MCP tool call: {name}")


def test_stream_turn_streams_question_answers_and_uses_projection_fallback(monkeypatch) -> None:
    AskFlowDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", AskFlowDocMcpClient)
    provider = FakeProvider([["Bản tóm tắt CV: ", "7 năm kinh nghiệm Java."]])

    events = asyncio.run(
        _collect_events(
            provider,
            prompt="Tóm tắt CV này đi",
            mode="agent",
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
    assert client.projection_calls == [("session-ask", True)]
    assert [name for name, _arguments in client.tool_calls] == ["answer_about_document"]

    streamed_text = "".join(data for event_type, data in events if event_type == "assistant_delta")
    assert streamed_text == "Bản tóm tắt CV: 7 năm kinh nghiệm Java."

    assert any(
        event_type == "tool_started" and data["tool"] == "get_html_projection"
        for event_type, data in events
    )
    assert any(
        event_type == "tool_started" and data["tool"] == "llm_inference" and data["phase"] == "compose_answer"
        for event_type, data in events
    )

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["message"] == streamed_text
    assert final_payload["resultType"] == "answer"

    user_prompt = provider.stream_calls[0][-1]["content"]
    assert "Document projection excerpt" in user_prompt
    assert "7 năm kinh nghiệm phát triển Java" in user_prompt


def test_stream_turn_uses_streaming_for_agent_planning(monkeypatch) -> None:
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
            prompt="Sửa phần mở đầu giúp tôi",
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
    assert [name for name, _arguments in client.tool_calls] == ["inspect_document", "locate_relevant_context"]
    assert any(
        event_type == "tool_started" and data["tool"] == "llm_inference" and data["phase"] == "plan_revision"
        for event_type, data in events
    )

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["message"] == "Không cần thay đổi nào."
    assert final_payload["resultType"] == "answer"


class EditResolutionDocMcpClient:
    instances: list["EditResolutionDocMcpClient"] = []

    def __init__(self) -> None:
        self.tool_calls: list[tuple[str, dict[str, Any]]] = []
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

    planning_prompt = provider.stream_calls[0][-1]["content"]
    assert "Resolved edit targets" in planning_prompt
    assert "p-17" in planning_prompt
    assert "PERSONAL DETAILS" in planning_prompt

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["resultType"] == "revision_staged"
    assert final_payload["revisionId"] == "rev-pending"
    assert final_payload["message"].startswith("Tôi sẽ thay Name: Bach Quang Linh")