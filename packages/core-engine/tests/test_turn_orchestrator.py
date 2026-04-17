import asyncio
import json
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


class FailingProvider(FakeProvider):
    async def stream_chat(self, messages: list[dict], model: str | None = None):
        self.stream_calls.append(messages)
        raise RuntimeError("provider exploded before streaming a response")
        yield ""


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


class NoToolDocMcpClient:
    instances: list["NoToolDocMcpClient"] = []

    def __init__(self) -> None:
        self.tool_calls: list[tuple[str, dict[str, Any]]] = []
        self.source_calls: list[str] = []
        NoToolDocMcpClient.instances.append(self)

    async def get_session_summary(self, session_id: str) -> dict[str, Any]:
        raise AssertionError("Agent greeting flow should not fetch a session summary.")

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        self.tool_calls.append((name, arguments))
        raise AssertionError(f"Agent greeting flow should not call MCP tool {name}.")

    async def get_source_html(self, session_id: str) -> str:
        self.source_calls.append(session_id)
        raise AssertionError("Agent greeting flow should not fetch source HTML.")

    async def get_analysis_html(self, session_id: str) -> str:
        raise AssertionError("Agent greeting flow should not fetch analysis HTML.")


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


def test_stream_turn_agent_mode_answers_simple_greeting_without_tools(monkeypatch) -> None:
    NoToolDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", NoToolDocMcpClient)
    provider = FakeProvider(
        [[
            '{"action":"final","result_type":"answer","assistant_message":"Chào bạn, mình sẵn sàng hỗ trợ với tài liệu này.","summary":"","operations":[],"risks":[],"needs_review":false}'
        ]]
    )

    events = asyncio.run(
        _collect_events(
            provider,
            prompt="hi",
            mode="agent",
            document_session_id="session-greeting",
            base_revision_id=None,
            selection=None,
            workspace_context=None,
            history=[],
        )
    )

    assert provider.chat_calls == 0
    assert len(provider.stream_calls) == 1

    client = NoToolDocMcpClient.instances[0]
    assert client.tool_calls == []
    assert client.source_calls == []

    llm_started = next(
        data for event_type, data in events if event_type == "tool_started" and data["tool"] == "llm_inference"
    )
    assert llm_started["phase"] == "agent_loop"
    assert llm_started["inputChars"] > len("hi")
    assert llm_started["estimatedInputTokens"] > 1

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["message"] == "Chào bạn, mình sẵn sàng hỗ trợ với tài liệu này."
    assert final_payload["resultType"] == "answer"
    assert final_payload["usage"]["requestCount"] == 1
    assert final_payload["usage"]["requests"][0]["phase"] == "agent_loop"

    planning_prompt = provider.stream_calls[0][-1]["content"]
    assert "User request:\nhi" in planning_prompt
    assert "Tool result" not in planning_prompt
    assert "Document source HTML excerpt" not in planning_prompt


def test_stream_turn_agent_mode_calls_tools_only_when_needed(monkeypatch) -> None:
    PlanningFlowDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", PlanningFlowDocMcpClient)
    provider = FakeProvider(
        [
            ['{"action":"call_tool","tool_name":"answer_about_document","arguments":{"question":"Tóm tắt CV này đi"}}'
             '{"action":"call_tool","tool_name":"inspect_document","arguments":{"include_outline":true,"include_style_summary":true}}'
             '{"action":"final","result_type":"answer","assistant_message":"Không nên dùng trực tiếp output này.","summary":"","operations":[],"risks":[],"needs_review":false}'],
            ['{"action":"final","result_type":"answer","assistant_message":"Ứng viên có đoạn giới thiệu hiện tại nói về kinh nghiệm mở đầu của CV.","summary":"","operations":[],"risks":[],"needs_review":false}'],
        ]
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
    assert len(provider.stream_calls) == 2

    client = PlanningFlowDocMcpClient.instances[0]
    assert [name for name, _arguments in client.tool_calls] == ["answer_about_document"]
    assert client.source_calls == []
    assert any(
        event_type == "tool_started" and data["tool"] == "llm_inference" and data["phase"] == "agent_loop"
        for event_type, data in events
    )
    llm_finished = next(
        data for event_type, data in events if event_type == "tool_finished" and data["tool"] == "llm_inference"
    )
    assert llm_finished["requestIndex"] == 1
    assert llm_finished["phase"] == "agent_loop"
    assert llm_finished["estimatedTotalTokens"] > 0

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["message"] == "Ứng viên có đoạn giới thiệu hiện tại nói về kinh nghiệm mở đầu của CV."
    assert final_payload["resultType"] == "answer"
    assert final_payload["usage"]["requestCount"] == 2
    assert all(request["phase"] == "agent_loop" for request in final_payload["usage"]["requests"])

    first_prompt = provider.stream_calls[0][-1]["content"]
    second_prompt = provider.stream_calls[1][-1]["content"]
    assert "Document source HTML excerpt" not in first_prompt
    assert "Tool batch result." in second_prompt
    assert "Step 1 (step_1): answer_about_document" in second_prompt
    assert "Current introduction paragraph." in second_prompt
    assert "Không nên dùng trực tiếp output này." not in final_payload["message"]


class BatchFlowDocMcpClient:
    instances: list["BatchFlowDocMcpClient"] = []

    def __init__(self) -> None:
        self.tool_calls: list[tuple[str, dict[str, Any]]] = []
        BatchFlowDocMcpClient.instances.append(self)

    async def get_session_summary(self, session_id: str) -> dict[str, Any]:
        return {
            "session_id": session_id,
            "state": "READY",
            "current_revision_id": "rev-batch",
            "section_count": 2,
        }

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        self.tool_calls.append((name, arguments))
        if name == "inspect_document":
            return {
                "metadata": {"section_count": 2},
                "outline": [{"block_id": "heading-1", "text": "SUMMARY", "level": 1}],
                "style_usage": {"Normal": 12},
            }
        raise AssertionError(f"Unexpected MCP tool call: {name}")


def test_stream_turn_supports_parallel_lightweight_tool_batches(monkeypatch) -> None:
    BatchFlowDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", BatchFlowDocMcpClient)
    provider = FakeProvider(
        [
            [
                '{"action":"call_tools","execution":"parallel","batch_reason":"Need session state and structure before answering","tool_calls":['
                '{"id":"step_summary","tool_name":"get_session_summary","arguments":{},"reason":"Need current revision id"},'
                '{"id":"step_inspect","tool_name":"inspect_document","arguments":{"include_outline":true,"include_style_summary":true},"reason":"Need structure overview"}'
                ']}'
            ],
            [
                '{"action":"final","result_type":"answer","assistant_message":"Tài liệu đang ở trạng thái READY và có 2 section chính.","summary":"","operations":[],"risks":[],"needs_review":false}'
            ],
        ]
    )

    events = asyncio.run(
        _collect_events(
            provider,
            prompt="Cho tôi overview nhanh",
            mode="agent",
            document_session_id="session-batch",
            base_revision_id=None,
            selection=None,
            workspace_context=None,
            history=[],
        )
    )

    client = BatchFlowDocMcpClient.instances[0]
    assert [name for name, _arguments in client.tool_calls] == ["inspect_document"]
    assert any(
        event_type == "tool_started" and data["tool"] == "tool_batch" and data["execution"] == "parallel"
        for event_type, data in events
    )

    second_prompt = provider.stream_calls[1][-1]["content"]
    assert "Tool batch result." in second_prompt
    assert "Step 1 (step_summary): get_session_summary" in second_prompt
    assert "Step 2 (step_inspect): inspect_document" in second_prompt

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["message"] == "Tài liệu đang ở trạng thái READY và có 2 section chính."
    assert final_payload["usage"]["requestCount"] == 2


def test_stream_turn_rejects_invalid_parallel_heavy_batch_and_replans(monkeypatch) -> None:
    NoToolDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", NoToolDocMcpClient)
    provider = FakeProvider(
        [
            [
                '{"action":"call_tools","execution":"parallel","batch_reason":"Try everything at once","tool_calls":['
                '{"id":"step_source","tool_name":"get_source_html","arguments":{},"reason":"Need full fidelity"},'
                '{"id":"step_analysis","tool_name":"get_analysis_html","arguments":{},"reason":"Need compact html too"}'
                ']}'
            ],
            [
                '{"action":"final","result_type":"answer","assistant_message":"Mình sẽ cần chọn một hướng rẻ hơn trước khi đọc HTML nặng.","summary":"","operations":[],"risks":[],"needs_review":false}'
            ],
        ]
    )

    events = asyncio.run(
        _collect_events(
            provider,
            prompt="đọc toàn bộ doc rồi trả lời",
            mode="agent",
            document_session_id="session-batch-reject",
            base_revision_id=None,
            selection=None,
            workspace_context=None,
            history=[],
        )
    )

    client = NoToolDocMcpClient.instances[0]
    assert client.tool_calls == []
    assert client.source_calls == []
    assert not any(event_type == "tool_started" and data["tool"] == "tool_batch" for event_type, data in events)

    second_prompt = provider.stream_calls[1][-1]["content"]
    assert "Tool batch rejection." in second_prompt
    assert "get_source_html" in second_prompt
    assert "cannot run in a parallel batch" in second_prompt

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["message"] == "Mình sẽ cần chọn một hướng rẻ hơn trước khi đọc HTML nặng."


def test_stream_turn_auto_compacts_session_context_under_budget(monkeypatch) -> None:
    NoToolDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", NoToolDocMcpClient)
    provider = FakeProvider(
        [[
            '{"action":"final","result_type":"answer","assistant_message":"Chào bạn, mình đã đọc context đã được nén.","summary":"","operations":[],"risks":[],"needs_review":false}'
        ]]
    )

    history = [
        {
            "role": "user" if index % 2 == 0 else "assistant",
            "content": f"history-{index}: " + ("x" * 360),
        }
        for index in range(12)
    ]

    events = asyncio.run(
        _collect_events(
            provider,
            prompt="hi",
            mode="agent",
            document_session_id="session-compaction",
            base_revision_id=None,
            selection=None,
            workspace_context=None,
            history=history,
            execution_config={
                "maxInputTokens": 1_500,
                "sessionContextBudgetTokens": 700,
                "toolResultBudgetTokens": 500,
            },
        )
    )

    llm_started = next(
        data for event_type, data in events if event_type == "tool_started" and data["tool"] == "llm_inference"
    )
    assert llm_started["estimatedInputTokens"] <= 1_500
    assert llm_started["compactedInput"] is True
    assert any(
        event_type == "tool_finished" and data["tool"] == "compact_session_context"
        for event_type, data in events
    )

    planning_messages = provider.stream_calls[0]
    assert any(
        message["role"] == "system" and "Compacted session history from earlier turns and tool results" in message["content"]
        for message in planning_messages
    )

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert any(notice.get("code") == "context_compacted" for notice in final_payload["notices"])


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
        [
            ['{"action":"call_tool","tool_name":"locate_relevant_context","arguments":{"query":"sửa Name: Bach Quang Linh trong PERSONAL DETAILS thành Bach Quang Link"}}'],
            ['{"action":"call_tool","tool_name":"locate_relevant_context","arguments":{"query":"Name: Bach Quang Linh"}}'],
            ['{"action":"call_tool","tool_name":"get_context_window","arguments":{"block_id":"p-17","window_size":2}}'],
            ['{"action":"final","result_type":"propose_edit","assistant_message":"Tôi sẽ thay Name: Bach Quang Linh thành Bach Quang Link trong PERSONAL DETAILS và stage bản sửa để bạn review.","summary":"Rename the candidate in personal details","operations":[{"op":"REPLACE_TEXT_RANGE","target":{"block_id":"p-17","start":6,"end":21},"value":{"text":"Bach Quang Link"},"description":"Rename the candidate"}],"risks":[],"needs_review":true}'],
        ]
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
    ]
    assert client.source_calls == []

    planning_prompt = provider.stream_calls[-1][-1]["content"]
    assert "Document source HTML excerpt" not in planning_prompt
    assert "Tool batch result." in planning_prompt
    assert "Step 1 (step_1): get_context_window" in planning_prompt
    assert "p-17" in planning_prompt
    assert "PERSONAL DETAILS" in planning_prompt

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["resultType"] == "revision_staged"
    assert final_payload["revisionId"] == "rev-pending"
    assert final_payload["message"].startswith("Tôi sẽ thay Name: Bach Quang Linh")
    assert final_payload["usage"]["requestCount"] == 4
    assert final_payload["usage"]["estimatedTotalTokens"] > 0


def test_stream_turn_omits_empty_base_revision_id_for_initial_revision(monkeypatch) -> None:
    InitialEditDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", InitialEditDocMcpClient)
    provider = FakeProvider(
        [
            ['{"action":"call_tool","tool_name":"locate_relevant_context","arguments":{"query":"sửa Name: Bach Quang Linh trong PERSONAL DETAILS thành Bach Quang Link"}}'],
            ['{"action":"call_tool","tool_name":"locate_relevant_context","arguments":{"query":"Name: Bach Quang Linh"}}'],
            ['{"action":"call_tool","tool_name":"get_context_window","arguments":{"block_id":"p-17","window_size":2}}'],
            ['{"action":"final","result_type":"propose_edit","assistant_message":"Tôi sẽ stage bản sửa đầu tiên để bạn review.","summary":"Stage the first revision","operations":[{"op":"REPLACE_TEXT_RANGE","target":{"block_id":"p-17","start":6,"end":21},"value":{"text":"Bach Quang Link"},"description":"Rename the candidate"}],"risks":[],"needs_review":true}'],
        ]
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


def test_stream_turn_records_input_metrics_before_provider_failure(monkeypatch) -> None:
    NoToolDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", NoToolDocMcpClient)
    provider = FailingProvider([])

    async def collect_events_before_failure() -> list[tuple[str, Any]]:
        events: list[tuple[str, Any]] = []
        try:
            async for event in turn_orchestrator.stream_turn(
                provider,
                prompt="hi",
                mode="agent",
                document_session_id="session-failure",
                base_revision_id=None,
                selection=None,
                workspace_context=None,
                history=[],
            ):
                events.append(event)
        except RuntimeError:
            return events
        raise AssertionError("Expected the provider to fail.")

    events = asyncio.run(collect_events_before_failure())

    llm_started = next(
        data for event_type, data in events if event_type == "tool_started" and data["tool"] == "llm_inference"
    )
    assert llm_started["phase"] == "agent_loop"
    assert llm_started["inputChars"] > len("hi")
    assert llm_started["estimatedInputTokens"] > 1
    assert llm_started["estimatedOutputTokens"] == 0


def test_stream_turn_fails_without_leaking_raw_model_output_on_invalid_json(monkeypatch) -> None:
    NoToolDocMcpClient.instances.clear()
    monkeypatch.setattr(turn_orchestrator, "DocMcpClient", NoToolDocMcpClient)
    provider = FakeProvider([["not-json-response"]])

    events = asyncio.run(
        _collect_events(
            provider,
            prompt="hi",
            mode="agent",
            document_session_id="session-invalid-json",
            base_revision_id=None,
            selection=None,
            workspace_context=None,
            history=[],
        )
    )

    assert not any(event_type == "assistant_delta" for event_type, _data in events)

    final_payload = next(data for event_type, data in events if event_type == "done")
    assert final_payload["status"] == "failed"
    assert final_payload["message"] == "The assistant returned an invalid internal response. Please try again."
    assert "not-json-response" not in final_payload["message"]
    assert final_payload["notices"][0]["code"] == "agent_invalid_json"
    assert "not-json-response" not in json.dumps(final_payload["notices"], ensure_ascii=False)