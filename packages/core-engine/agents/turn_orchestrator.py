import asyncio
import json
import math
import re
from typing import Any, AsyncIterator

from config import settings
from debug_logging import get_trace_id, log_core_event
from providers.base import BaseProvider
from services.doc_mcp_client import DocMcpClient

_EDIT_REWRITE_PATTERN = re.compile(
    r"(?is)\b(?:replace|change|update|modify|fix|correct|edit|rewrite|sửa|chỉnh(?:[\s\-]*lại)?|thay(?:[\s\-]*(?:đổi|thế))?|cập[\s\-]*nhật)\s+"
    r"(?P<source>.+?)"
    r"(?:\s+(?:in|under|inside|within|trong|ở|mục|phần)\s+(?P<section>.+?))?"
    r"\s+(?:to|with|into|thành|bằng)\s+(?P<replacement>.+)$",
    re.IGNORECASE | re.UNICODE,
)
_QUOTED_TEXT_PATTERN = re.compile(r"[\"'“”‘’](.+?)[\"'“”‘’]", re.DOTALL)
_JSON_FENCE_PATTERN = re.compile(r"```json\s*(\{.*?\})\s*```", re.DOTALL | re.IGNORECASE)
_MAX_AGENT_LOOP_STEPS = 8
_AGENT_LOOP_PHASE = "agent_loop"
_CONTEXT_COMPACTION_TOOL = "compact_session_context"
_TOOL_BATCH_EVENT = "tool_batch"
_AGENT_TOOL_SPECS: dict[str, dict[str, Any]] = {
    "get_session_summary": {
        "tier": "light",
        "supports_parallel": True,
        "max_calls_per_turn": 2,
        "estimated_result_tokens": 140,
        "guidance": "Load session state, filename, and current revision id before heavier planning.",
    },
    "answer_about_document": {
        "tier": "medium",
        "supports_parallel": False,
        "max_calls_per_turn": 2,
        "estimated_result_tokens": 520,
        "guidance": "Use semantic/snippet retrieval when you need topical document evidence for a question.",
    },
    "inspect_document": {
        "tier": "light",
        "supports_parallel": True,
        "max_calls_per_turn": 2,
        "estimated_result_tokens": 260,
        "guidance": "Inspect outline, sections, and style usage when structure matters.",
    },
    "locate_relevant_context": {
        "tier": "medium",
        "supports_parallel": False,
        "max_calls_per_turn": 3,
        "estimated_result_tokens": 480,
        "guidance": "Find block ids or likely targets for edits and follow up with context windows.",
    },
    "get_context_window": {
        "tier": "light",
        "supports_parallel": True,
        "max_calls_per_turn": 4,
        "estimated_result_tokens": 360,
        "guidance": "Fetch a local neighborhood around a known block id.",
    },
    "get_source_html": {
        "tier": "heavy",
        "supports_parallel": False,
        "max_calls_per_turn": 1,
        "estimated_result_tokens": 1800,
        "guidance": "Use only when fidelity to exact source structure or formatting is required.",
    },
    "get_analysis_html": {
        "tier": "heavy",
        "supports_parallel": False,
        "max_calls_per_turn": 1,
        "estimated_result_tokens": 1200,
        "guidance": "Use when compact analysis HTML is sufficient and cheaper tools were not enough.",
    },
}
_AGENT_TOOL_NAMES = set(_AGENT_TOOL_SPECS)

ASK_SYSTEM_PROMPT = """You are DocPilot running in ASK mode.

Rules:
- ASK mode is read-only. Never say you changed the document.
- If the user asks for edits, explain that ASK mode cannot mutate the document and that they should switch to AGENT mode.
- Use only the supplied document context.
- The active document session is already the subject of requests like "this document", "this CV", or "đọc doc trong này".
- If document snippets, outline data, or analysis HTML are supplied, answer directly from them and do not ask the user to paste the document again.
- If the context is insufficient, say exactly what is missing.
- Reply in the same language as the user.
"""

def _tool_registry_prompt() -> str:
        lines: list[str] = []
        for tool_name, spec in _AGENT_TOOL_SPECS.items():
                parallel = "yes" if spec["supports_parallel"] else "no"
                lines.append(
                        f"- {tool_name}: cost={spec['tier']}; parallel={parallel}; recommended max {spec['max_calls_per_turn']}/turn; {spec['guidance']}"
                )
        return "\n".join(lines)


def _build_agent_system_prompt(execution_config: dict[str, Any]) -> str:
        return f"""You are DocPilot running in AGENT mode.

Return JSON only. No markdown fences. No prose before or after the JSON.

Primary batch tool schema:
{{
    "action": "call_tools",
    "execution": "parallel" | "sequential",
    "batch_reason": "string",
    "tool_calls": [
        {{
            "id": "step_1",
            "tool_name": "get_session_summary" | "answer_about_document" | "inspect_document" | "locate_relevant_context" | "get_context_window" | "get_source_html" | "get_analysis_html",
            "arguments": {{}},
            "reason": "string"
        }}
    ]
}}

Backward-compatible single-tool schema:
{{
    "action": "call_tool",
    "tool_name": "get_session_summary" | "answer_about_document" | "inspect_document" | "locate_relevant_context" | "get_context_window" | "get_source_html" | "get_analysis_html",
    "arguments": {{}}
}}

Final response schema:
{{
    "action": "final",
    "result_type": "answer" | "clarify" | "propose_edit",
    "assistant_message": "string",
    "summary": "short revision summary",
    "operations": [
        {{
            "op": "REPLACE_TEXT_RANGE" | "INSERT_TEXT_AT" | "DELETE_TEXT_RANGE" | "APPLY_STYLE" | "APPLY_INLINE_FORMAT" | "SET_HEADING_LEVEL" | "CREATE_BLOCK" | "UPDATE_CELL_CONTENT" | "CHANGE_LIST_TYPE" | "CHANGE_LIST_LEVEL",
            "target": {{
                "block_id": "string",
                "start": 0,
                "end": 10,
                "table_id": "string",
                "row_id": "string",
                "cell_id": "string",
                "cell_logical_address": "R1C1"
            }},
            "value": {{}},
            "description": "string"
        }}
    ],
    "risks": ["string"],
    "needs_review": true
}}

Tool argument guide:
- get_session_summary: {{}}
- answer_about_document: {{"question": "string"}}
- inspect_document: {{"include_outline": true, "include_style_summary": true}}
- locate_relevant_context: {{"query": "string"}}
- get_context_window: {{"block_id": "string", "window_size": 2}}
- get_source_html: {{}}
- get_analysis_html: {{}}

Execution budget and control:
- Approximate max input tokens per LLM request: {execution_config['max_input_tokens']}
- Approximate session-context budget before compaction: {execution_config['session_context_budget_tokens']}
- Approximate tool-result budget per batch: {execution_config['tool_result_budget_tokens']}
- Max tool calls in one batch: {execution_config['max_tool_batch_size']}
- Max tools that may run in parallel: {execution_config['max_parallel_tools']}
- Max heavy tools per turn: {execution_config['max_heavy_tools_per_turn']}

Tool registry:
{_tool_registry_prompt()}

Rules:
- Answer greetings and simple chat directly without tools.
- Prefer cheaper tools first and only call tools when they materially help.
- Use action="call_tools" when you want multiple tools in one loop step.
- Use execution="parallel" only when every tool is independent and light.
- Use execution="sequential" when later tools depend on earlier results, or any tool is medium or heavy.
- Never batch more than one heavy tool.
- If the executor rejects a batch, you will receive a Tool batch rejection message. Replan with fewer, cheaper, or ordered tools.
- After each successful tool batch, you will receive a follow-up user message labeled Tool batch result with each step result.
- The executor may compact older session history and tool results automatically to stay under the input budget.
- For edits, gather explicit block ids or table or cell ids from tool results before propose_edit.
- Never invent block ids, table ids, row ids, or cell ids.
- Use result_type="clarify" when the request is ambiguous or unsafe to execute.
- Use result_type="answer" when no document mutation is required.
- Use result_type="propose_edit" only when you can target explicit ids from tool output or the current selection context.
- Prefer the smallest operation set that satisfies the request.
- Use snake_case target keys exactly as shown above.
- For REPLACE_TEXT_RANGE and UPDATE_CELL_CONTENT, put the replacement text in value.text.
- For APPLY_STYLE, use value.style_id.
- For SET_HEADING_LEVEL or CHANGE_LIST_LEVEL, use value.level.
- For CREATE_BLOCK, use value.type and value.text at minimum.
- assistant_message must summarize what will be staged for review, or ask the clarifying question.
- Reply in the same language as the user inside assistant_message.
"""


def _filtered_history_messages(
        history: list[dict[str, str]],
        prompt: str | None = None,
        *,
        strip_current_user_prompt: bool = False,
) -> list[dict[str, str]]:
        messages: list[dict[str, str]] = []

        for message in history:
                role = message.get("role")
                content = message.get("content", "")
                if role in {"system", "user", "assistant"}:
                        messages.append({"role": role, "content": content})

        if strip_current_user_prompt and prompt is not None and messages:
                last_message = messages[-1]
                if last_message.get("role") == "user" and str(last_message.get("content", "")).strip() == prompt.strip():
                        messages.pop()

        return messages


def _build_messages(
    system_prompt: str,
    prompt: str,
    history: list[dict[str, str]],
    context_blocks: list[str],
) -> list[dict[str, str]]:
    messages: list[dict[str, str]] = [{"role": "system", "content": system_prompt}]

    messages.extend(_filtered_history_messages(history, prompt, strip_current_user_prompt=True))

    user_content = "\n\n".join(block for block in context_blocks if block)
    user_content = f"{user_content}\n\nUser request:\n{prompt}" if user_content else prompt
    messages.append({"role": "user", "content": user_content})
    return messages


def _build_agent_loop_messages(
    system_prompt: str,
    prompt: str,
    history: list[dict[str, str]],
    selection: dict[str, Any] | None,
    workspace_context: dict[str, Any] | None,
    loop_transcript: list[dict[str, str]],
) -> list[dict[str, str]]:
    messages: list[dict[str, str]] = [{"role": "system", "content": system_prompt}]
    messages.extend(_filtered_history_messages(history, prompt, strip_current_user_prompt=True))

    current_turn_blocks = [f"User request:\n{prompt}"]
    selection_block = _selection_block(selection)
    workspace_block = _workspace_block(workspace_context)
    if selection_block:
        current_turn_blocks.append(selection_block)
    if workspace_block:
        current_turn_blocks.append(workspace_block)
    messages.append({"role": "user", "content": "\n\n".join(current_turn_blocks)})
    messages.extend(loop_transcript)
    return messages


def _json_block(label: str, payload: Any) -> str:
    return f"{label}:\n{json.dumps(payload, ensure_ascii=False, indent=2)}"


def _text_block(label: str, text: str) -> str:
    stripped = text.strip()
    if not stripped:
        return ""
    return f"{label}:\n{stripped}"


def _compact_text(text: Any, max_chars: int = 400) -> str:
    normalized = re.sub(r"\s+", " ", str(text or "")).strip()
    if len(normalized) <= max_chars:
        return normalized
    return f"{normalized[:max_chars - 16].rstrip()} ...[truncated]"


def _compact_context_entry(entry: Any) -> Any:
    if not isinstance(entry, dict):
        return entry

    compact: dict[str, Any] = {}
    for key in (
        "block_id",
        "type",
        "text",
        "logical_path",
        "source",
        "match_offset",
        "match_length",
        "level",
        "isTarget",
        "blockId",
    ):
        value = entry.get(key)
        if value is None or value == "" or value == []:
            continue
        compact[key] = _compact_text(value, 300) if key == "text" else value

    return compact or entry


def _compact_context_list(items: Any, *, max_items: int) -> tuple[list[Any], int]:
    if not isinstance(items, list):
        return [], 0
    compact_items = [_compact_context_entry(item) for item in items[:max_items]]
    omitted = max(0, len(items) - len(compact_items))
    return compact_items, omitted


def _compact_tool_result(tool_name: str, result: Any) -> Any:
    if tool_name in {"get_source_html", "get_analysis_html"}:
        text = result if isinstance(result, str) else str(result or "")
        return {
            "content_length": len(text),
            "excerpt": _html_excerpt(text, max_chars=6_000),
        }

    if tool_name == "get_session_summary":
        return result

    if tool_name == "inspect_document" and isinstance(result, dict):
        compact = {
            "metadata": result.get("metadata"),
            "style_usage": result.get("style_usage"),
        }
        outline, omitted_outline = _compact_context_list(result.get("outline"), max_items=8)
        if outline:
            compact["outline"] = outline
        if omitted_outline:
            compact["omitted_outline_count"] = omitted_outline
        return compact

    if tool_name == "answer_about_document" and isinstance(result, dict):
        compact: dict[str, Any] = {
            key: result.get(key)
            for key in (
                "session_id",
                "filename",
                "word_count",
                "paragraph_count",
                "question",
                "semantic_search_enabled",
                "retrieval_provider",
            )
            if key in result
        }
        headings, omitted_headings = _compact_context_list(result.get("headings"), max_items=8)
        snippets, omitted_snippets = _compact_context_list(result.get("relevant_snippets"), max_items=5)
        if headings:
            compact["headings"] = headings
        if omitted_headings:
            compact["omitted_heading_count"] = omitted_headings
        compact["snippet_count"] = len(result.get("relevant_snippets", [])) if isinstance(result.get("relevant_snippets"), list) else 0
        if snippets:
            compact["relevant_snippets"] = snippets
        if omitted_snippets:
            compact["omitted_snippet_count"] = omitted_snippets
        return compact

    if tool_name == "locate_relevant_context" and isinstance(result, dict):
        compact: dict[str, Any] = {
            key: result.get(key)
            for key in ("query", "retrieval_provider", "match_count")
            if key in result
        }
        matches, omitted_matches = _compact_context_list(result.get("matches"), max_items=5)
        if matches:
            compact["matches"] = matches
        if omitted_matches:
            compact["omitted_match_count"] = omitted_matches
        return compact

    if tool_name == "get_context_window" and isinstance(result, dict):
        compact: dict[str, Any] = {}
        for key in ("focus", "target"):
            if isinstance(result.get(key), dict):
                compact[key] = _compact_context_entry(result[key])
        for key in ("before", "after", "window"):
            items, omitted = _compact_context_list(result.get(key), max_items=4)
            if items:
                compact[key] = items
            if omitted:
                compact[f"omitted_{key}_count"] = omitted
        return compact

    return result


def _tool_result_message(tool_name: str, arguments: dict[str, Any], result: Any) -> str:
    compact_result = _compact_tool_result(tool_name, result)
    result_block = _render_result_block("Result", compact_result, max_chars=2_400)
    return "\n\n".join(
        part
        for part in (
            f"Tool result for {tool_name}.",
            _json_block("Arguments", arguments),
            result_block,
            "Continue the same turn. Either call another tool or finish the turn.",
        )
        if part
    )


def _coerce_bool(value: Any, default: bool) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1", "yes"}:
            return True
        if normalized in {"false", "0", "no"}:
            return False
    return default


def _coerce_window_size(value: Any, default: int = 2) -> int:
    if isinstance(value, bool):
        return default
    if isinstance(value, int):
        return max(1, min(value, 5))
    if isinstance(value, str):
        try:
            return max(1, min(int(value.strip()), 5))
        except ValueError:
            return default
    return default


def _coerce_int(value: Any, fallback: int, *, minimum: int, maximum: int) -> int:
    if isinstance(value, bool):
        return fallback

    candidate = fallback
    if isinstance(value, int):
        candidate = value
    elif isinstance(value, float) and value.is_integer():
        candidate = int(value)
    elif isinstance(value, str):
        try:
            candidate = int(value.strip())
        except ValueError:
            candidate = fallback

    return max(minimum, min(candidate, maximum))


def _coerce_float(value: Any, fallback: float, *, minimum: float, maximum: float) -> float:
    candidate = fallback
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        candidate = float(value)
    elif isinstance(value, str):
        try:
            candidate = float(value.strip())
        except ValueError:
            candidate = fallback

    return max(minimum, min(candidate, maximum))


def _resolve_agent_execution_config(execution_config: dict[str, Any] | None) -> dict[str, Any]:
    raw = execution_config if isinstance(execution_config, dict) else {}
    max_input_tokens = _coerce_int(
        raw.get("max_input_tokens") or raw.get("maxInputTokens"),
        settings.agent_max_input_tokens,
        minimum=1_200,
        maximum=200_000,
    )
    session_context_budget_tokens = _coerce_int(
        raw.get("session_context_budget_tokens") or raw.get("sessionContextBudgetTokens"),
        settings.agent_session_context_budget_tokens,
        minimum=400,
        maximum=max(400, max_input_tokens - 160),
    )
    tool_result_budget_tokens = _coerce_int(
        raw.get("tool_result_budget_tokens") or raw.get("toolResultBudgetTokens"),
        settings.agent_tool_result_budget_tokens,
        minimum=240,
        maximum=max(240, max_input_tokens - 80),
    )
    max_tool_batch_size = _coerce_int(
        raw.get("max_tool_batch_size") or raw.get("maxToolBatchSize"),
        settings.agent_max_tool_batch_size,
        minimum=1,
        maximum=8,
    )
    max_parallel_tools = _coerce_int(
        raw.get("max_parallel_tools") or raw.get("maxParallelTools"),
        settings.agent_max_parallel_tools,
        minimum=1,
        maximum=max_tool_batch_size,
    )
    max_heavy_tools_per_turn = _coerce_int(
        raw.get("max_heavy_tools_per_turn") or raw.get("maxHeavyToolsPerTurn"),
        settings.agent_max_heavy_tools_per_turn,
        minimum=1,
        maximum=max_tool_batch_size,
    )

    return {
        "max_input_tokens": max_input_tokens,
        "session_context_budget_tokens": min(session_context_budget_tokens, max_input_tokens - 80),
        "tool_result_budget_tokens": min(tool_result_budget_tokens, max_input_tokens - 40),
        "max_tool_batch_size": max_tool_batch_size,
        "max_parallel_tools": min(max_parallel_tools, max_tool_batch_size),
        "max_heavy_tools_per_turn": max_heavy_tools_per_turn,
        "auto_compact_session": _coerce_bool(
            raw.get("auto_compact_session") or raw.get("autoCompactSession"),
            settings.agent_auto_compact_session,
        ),
        "auto_compact_threshold": _coerce_float(
            raw.get("auto_compact_threshold") or raw.get("autoCompactThreshold"),
            settings.agent_auto_compact_threshold,
            minimum=0.55,
            maximum=0.98,
        ),
    }


def _render_result_block(label: str, payload: Any, *, max_chars: int) -> str:
    if isinstance(payload, str):
        return _text_block(label, _compact_text(payload, max_chars))

    rendered = json.dumps(payload, ensure_ascii=False, indent=2)
    if len(rendered) <= max_chars:
        return f"{label}:\n{rendered}"
    return _text_block(label, _compact_text(rendered, max_chars))


def _summarize_message_for_context(message: dict[str, str], *, max_chars: int) -> str:
    role = str(message.get("role") or "user").strip().lower() or "user"
    content = _compact_text(message.get("content", ""), max_chars)
    if not content:
        return ""
    return f"- {role}: {content}"


def _build_context_summary_message(messages: list[dict[str, str]], *, max_chars: int) -> dict[str, str] | None:
    if not messages:
        return None

    header = "Compacted session history from earlier turns and tool results:"
    per_message_chars = max(96, min(220, max_chars // max(1, len(messages))))
    lines: list[str] = []

    for message in messages:
        line = _summarize_message_for_context(message, max_chars=per_message_chars)
        if not line:
            continue

        candidate_lines = [*lines, line]
        candidate = "\n".join([header, *candidate_lines])
        if len(candidate) > max_chars and lines:
            break
        lines = candidate_lines

    if not lines:
        lines = ["- Earlier messages were compacted."]

    return {
        "role": "system",
        "content": "\n".join([header, *lines]),
    }


def _compact_messages_to_fit_budget(
    messages: list[dict[str, str]],
    execution_config: dict[str, Any],
    *,
    preserve_tail: int = 6,
) -> tuple[list[dict[str, str]], dict[str, Any] | None]:
    original_tokens = _estimate_messages_token_count(messages)
    session_budget = int(execution_config["session_context_budget_tokens"])
    max_input_tokens = int(execution_config["max_input_tokens"])
    threshold = float(execution_config["auto_compact_threshold"])
    should_compact = original_tokens > max_input_tokens or original_tokens >= int(session_budget * threshold)

    if not should_compact:
        return messages, None

    if original_tokens <= max_input_tokens and not execution_config["auto_compact_session"]:
        return messages, None

    normalized = [
        {
            "role": str(message.get("role") or "user"),
            "content": str(message.get("content", "")),
        }
        for message in messages
    ]

    if len(normalized) <= 2:
        return normalized, None

    preserve_tail = max(1, min(preserve_tail, len(normalized) - 1))
    prefix = normalized[:1]
    tail_messages = normalized[1:]
    summarized_message_count = 0

    if len(tail_messages) > preserve_tail:
        middle_messages = tail_messages[:-preserve_tail]
        kept_tail = tail_messages[-preserve_tail:]
        summary_message = _build_context_summary_message(
            middle_messages,
            max_chars=max(480, min(2_000, session_budget * 2)),
        )
        compacted = [*prefix]
        if summary_message:
            compacted.append(summary_message)
        compacted.extend(kept_tail)
        summarized_message_count = len(middle_messages)
    else:
        compacted = normalized

    final_messages = compacted
    final_tokens = _estimate_messages_token_count(final_messages)

    if final_tokens > max_input_tokens:
        per_message_chars = max(160, (max_input_tokens * 4) // max(2, len(final_messages)))
        tightened: list[dict[str, str]] = []
        for index, message in enumerate(final_messages):
            if index == 0 or index == len(final_messages) - 1:
                tightened.append(message)
                continue
            tightened.append({**message, "content": _compact_text(message.get("content", ""), per_message_chars)})
        final_messages = tightened
        final_tokens = _estimate_messages_token_count(final_messages)

    if final_tokens > max_input_tokens and len(final_messages) > 2:
        first_message = final_messages[0]
        last_message = final_messages[-1]
        available_summary_chars = max(
            180,
            (max_input_tokens * 4) - len(first_message.get("content", "")) - len(last_message.get("content", "")) - 120,
        )
        summary_message = _build_context_summary_message(
            final_messages[1:-1],
            max_chars=available_summary_chars,
        )
        final_messages = [first_message]
        if summary_message:
            final_messages.append(summary_message)
        final_messages.append(last_message)
        final_tokens = _estimate_messages_token_count(final_messages)

    compaction_info = {
        "reason": "session_context_budget",
        "original_estimated_input_tokens": original_tokens,
        "final_estimated_input_tokens": final_tokens,
        "summarized_message_count": summarized_message_count,
    }
    return final_messages, compaction_info


def _operations_have_explicit_targets(operations: list[dict[str, Any]]) -> bool:
    if not operations:
        return False

    target_keys = {"block_id", "table_id", "row_id", "cell_id", "cell_logical_address"}
    for operation in operations:
        target = operation.get("target")
        if not isinstance(target, dict):
            return False
        if not any(str(target.get(key) or "").strip() for key in target_keys):
            return False
    return True


def _selection_block(selection: dict[str, Any] | None) -> str:
    if not selection:
        return ""
    return _json_block("Selection", selection)


def _workspace_block(workspace_context: dict[str, Any] | None) -> str:
    if not workspace_context:
        return ""
    return _json_block("Workspace context", workspace_context)


def _tool_started(tool: str, **payload: Any) -> tuple[str, dict[str, Any]]:
    data = {"tool": tool}
    data.update(payload)
    return "tool_started", data


def _tool_finished(tool: str, **payload: Any) -> tuple[str, dict[str, Any]]:
    data = {"tool": tool}
    data.update(payload)
    return "tool_finished", data


def _estimate_token_count(text: str) -> int:
    stripped = text.strip()
    if not stripped:
        return 0
    return max(1, math.ceil(len(stripped) / 4))


def _estimate_messages_token_count(messages: list[dict[str, str]]) -> int:
    total = 0
    for message in messages:
        content = message.get("content", "")
        if isinstance(content, str):
            total += _estimate_token_count(content)
    return total


def _provider_display_name(provider: BaseProvider) -> str:
    display_name = str(getattr(provider, "provider_display_name", "") or "").strip()
    if display_name:
        return display_name

    provider_name = str(getattr(provider, "provider_name", "") or "").strip()
    if provider_name:
        return provider_name

    class_name = provider.__class__.__name__.removesuffix("Provider")
    return class_name or "AI model"


def _provider_name(provider: BaseProvider) -> str:
    provider_name = str(getattr(provider, "provider_name", "") or "").strip()
    if provider_name:
        return provider_name
    return provider.__class__.__name__.removesuffix("Provider").lower()


def _resolved_model_name(provider: BaseProvider) -> str | None:
    model = str(getattr(provider, "resolved_model", "") or getattr(provider, "default_model", "") or "").strip()
    return model or None


def _build_llm_request_metrics(
    *,
    request_index: int,
    phase: str,
    provider: BaseProvider,
    messages: list[dict[str, str]],
    output_text: str,
) -> dict[str, Any]:
    input_chars = sum(len(message.get("content", "")) for message in messages if isinstance(message.get("content"), str))
    output_chars = len(output_text)
    estimated_input_tokens = _estimate_messages_token_count(messages)
    estimated_output_tokens = _estimate_token_count(output_text)

    return {
        "requestIndex": request_index,
        "phase": phase,
        "provider": _provider_name(provider),
        "providerDisplayName": _provider_display_name(provider),
        "model": _resolved_model_name(provider),
        "inputChars": input_chars,
        "outputChars": output_chars,
        "estimatedInputTokens": estimated_input_tokens,
        "estimatedOutputTokens": estimated_output_tokens,
        "estimatedTotalTokens": estimated_input_tokens + estimated_output_tokens,
    }


def _summarize_llm_requests(requests: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "requestCount": len(requests),
        "estimatedInputTokens": sum(int(request.get("estimatedInputTokens") or 0) for request in requests),
        "estimatedOutputTokens": sum(int(request.get("estimatedOutputTokens") or 0) for request in requests),
        "estimatedTotalTokens": sum(int(request.get("estimatedTotalTokens") or 0) for request in requests),
        "requests": requests,
    }


def _chunk_text(text: str, chunk_size: int = 220) -> list[str]:
    stripped = text.strip()
    if not stripped:
        return []

    chunks: list[str] = []
    remaining = stripped
    while len(remaining) > chunk_size:
        split_at = remaining.rfind(" ", 0, chunk_size)
        if split_at <= 0:
            split_at = chunk_size
        chunks.append(remaining[:split_at])
        remaining = remaining[split_at:].lstrip()
    if remaining:
        chunks.append(remaining)
    return chunks


def _clean_candidate_text(value: str) -> str:
    cleaned = re.sub(r"\s+", " ", value or "").strip()
    return cleaned.strip(" \t\r\n\"'“”‘’`.,;:()[]{}")


def _extract_edit_search_candidates(prompt: str) -> list[dict[str, str]]:
    candidates: list[dict[str, str]] = []
    seen: set[str] = set()

    def add_candidate(label: str, query: str) -> None:
        cleaned = _clean_candidate_text(query)
        if len(cleaned) < 3:
            return
        normalized = cleaned.casefold()
        if normalized == (prompt or "").strip().casefold() or normalized in seen:
            return
        seen.add(normalized)
        candidates.append({"label": label, "query": cleaned})

    rewrite_match = _EDIT_REWRITE_PATTERN.search(prompt or "")
    if rewrite_match:
        source_text = rewrite_match.group("source") or ""
        section_hint = rewrite_match.group("section") or ""
        add_candidate("source_text", source_text)
        if ":" in source_text:
            add_candidate("source_value", source_text.split(":", 1)[1])
        add_candidate("section_hint", section_hint)

    for quoted in _QUOTED_TEXT_PATTERN.findall(prompt or ""):
        add_candidate("quoted_text", quoted)

    return candidates[:6]


def _has_answer_snippets(answer_context: Any) -> bool:
    if not isinstance(answer_context, dict):
        return False

    snippets = answer_context.get("relevant_snippets")
    if not isinstance(snippets, list):
        return False

    return any(isinstance(item, dict) and str(item.get("text", "")).strip() for item in snippets)


def _has_relevant_matches(context: Any) -> bool:
    if not isinstance(context, dict):
        return False

    matches = context.get("matches")
    if not isinstance(matches, list):
        return False

    return any(isinstance(item, dict) and str(item.get("block_id", "")).strip() for item in matches)


def _collect_match_block_ids(context: Any) -> list[str]:
    if not isinstance(context, dict):
        return []

    matches = context.get("matches")
    if not isinstance(matches, list):
        return []

    block_ids: list[str] = []
    seen: set[str] = set()
    for item in matches:
        if not isinstance(item, dict):
            continue
        block_id = str(item.get("block_id") or "").strip()
        if block_id and block_id not in seen:
            seen.add(block_id)
            block_ids.append(block_id)
    return block_ids


def _html_excerpt(html: str, max_chars: int = 12_000) -> str:
    if not html:
        return ""

    normalized = html.replace("\r", "").strip()
    normalized = re.sub(r">\s+<", "><", normalized)
    normalized = re.sub(r"[ \t]+", " ", normalized)
    normalized = re.sub(r"\n{3,}", "\n\n", normalized)
    if len(normalized) <= max_chars:
        return normalized

    head_budget = max_chars - 1_600
    head = normalized[:head_budget].rstrip()
    tail = normalized[-1_200:].lstrip()
    return f"{head}\n\n<!-- html excerpt truncated -->\n\n{tail}".strip()


def _extract_json_object(text: str) -> dict[str, Any] | None:
    objects = _extract_json_objects(text)
    return objects[0] if objects else None


def _extract_json_objects(text: str) -> list[dict[str, Any]]:
    if not text:
        return []

    candidate = text.strip()
    if not candidate:
        return []

    if candidate.startswith("```"):
        candidate = re.sub(r"^```json\s*", "", candidate, flags=re.IGNORECASE)
        candidate = re.sub(r"^```\s*", "", candidate)
        candidate = re.sub(r"\s*```$", "", candidate)
        candidate = candidate.strip()

    decoder = json.JSONDecoder()
    objects: list[dict[str, Any]] = []
    index = 0
    length = len(candidate)

    while index < length:
        while index < length and candidate[index].isspace():
            index += 1

        if index >= length:
            break

        if candidate[index] != "{":
            next_brace = candidate.find("{", index)
            if next_brace < 0:
                break
            index = next_brace

        try:
            parsed, end_index = decoder.raw_decode(candidate, index)
        except json.JSONDecodeError:
            next_brace = candidate.find("{", index + 1)
            if next_brace < 0:
                break
            index = next_brace
            continue

        if isinstance(parsed, dict):
            objects.append(parsed)
        index = end_index

    return objects


def _select_agent_action(action_payloads: list[dict[str, Any]]) -> dict[str, Any] | None:
    for payload in action_payloads:
        action = str(payload.get("action") or "").strip().lower()
        if action in {"call_tool", "call_tools", "final"}:
            return payload
    return action_payloads[0] if action_payloads else None


def _normalize_tool_batch_plan(action_payload: dict[str, Any]) -> dict[str, Any]:
    action = str(action_payload.get("action") or "").strip().lower()
    if action == "call_tool":
        return {
            "execution": "sequential",
            "batch_reason": str(action_payload.get("reason") or "single tool call").strip(),
            "tool_calls": [
                {
                    "id": "step_1",
                    "tool_name": str(action_payload.get("tool_name") or "").strip(),
                    "arguments": action_payload.get("arguments") if isinstance(action_payload.get("arguments"), dict) else {},
                    "reason": str(action_payload.get("reason") or "").strip(),
                }
            ],
        }

    raw_tool_calls = action_payload.get("tool_calls") if isinstance(action_payload.get("tool_calls"), list) else []
    tool_calls: list[dict[str, Any]] = []
    for index, tool_call in enumerate(raw_tool_calls, start=1):
        if not isinstance(tool_call, dict):
            continue
        tool_calls.append(
            {
                "id": str(tool_call.get("id") or f"step_{index}").strip() or f"step_{index}",
                "tool_name": str(tool_call.get("tool_name") or "").strip(),
                "arguments": tool_call.get("arguments") if isinstance(tool_call.get("arguments"), dict) else {},
                "reason": str(tool_call.get("reason") or "").strip(),
            }
        )

    return {
        "execution": str(action_payload.get("execution") or "sequential").strip().lower(),
        "batch_reason": str(action_payload.get("batch_reason") or "").strip(),
        "tool_calls": tool_calls,
    }


def _estimate_tool_batch_result_tokens(tool_calls: list[dict[str, Any]]) -> int:
    total = 0
    for tool_call in tool_calls:
        spec = _AGENT_TOOL_SPECS.get(tool_call.get("tool_name"))
        if spec:
            total += int(spec["estimated_result_tokens"])
    return total


def _validate_tool_batch(
    batch_plan: dict[str, Any],
    execution_config: dict[str, Any],
    tool_usage_counts: dict[str, int],
    heavy_tool_calls_used: int,
    current_input_tokens: int,
) -> tuple[list[str], dict[str, Any]]:
    execution = str(batch_plan.get("execution") or "sequential").strip().lower()
    tool_calls = batch_plan.get("tool_calls") if isinstance(batch_plan.get("tool_calls"), list) else []
    planned_result_tokens = _estimate_tool_batch_result_tokens(tool_calls)
    projected_next_input_tokens = current_input_tokens + planned_result_tokens + 120
    within_batch_counts: dict[str, int] = {}
    heavy_in_batch = 0
    errors: list[str] = []

    if execution not in {"parallel", "sequential"}:
        errors.append("execution must be either `parallel` or `sequential`.")

    if not tool_calls:
        errors.append("tool_calls must contain at least one tool step.")

    if len(tool_calls) > int(execution_config["max_tool_batch_size"]):
        errors.append(
            f"tool_calls length {len(tool_calls)} exceeds max batch size {execution_config['max_tool_batch_size']}."
        )

    if execution == "parallel" and len(tool_calls) > int(execution_config["max_parallel_tools"]):
        errors.append(
            f"parallel batches may include at most {execution_config['max_parallel_tools']} tools."
        )

    for tool_call in tool_calls:
        tool_name = str(tool_call.get("tool_name") or "").strip()
        spec = _AGENT_TOOL_SPECS.get(tool_name)
        if not spec:
            errors.append(f"Unsupported tool `{tool_name}` in tool_calls.")
            continue

        within_batch_counts[tool_name] = within_batch_counts.get(tool_name, 0) + 1
        total_count = tool_usage_counts.get(tool_name, 0) + within_batch_counts[tool_name]
        if total_count > int(spec["max_calls_per_turn"]):
            errors.append(
                f"{tool_name} would be used {total_count} times this turn, above its max of {spec['max_calls_per_turn']}."
            )

        if spec["tier"] == "heavy":
            heavy_in_batch += 1

        if execution == "parallel":
            if not bool(spec["supports_parallel"]):
                errors.append(f"{tool_name} does not support parallel execution.")
            if spec["tier"] != "light":
                errors.append(f"{tool_name} is {spec['tier']} cost and cannot run in a parallel batch.")

    if heavy_tool_calls_used + heavy_in_batch > int(execution_config["max_heavy_tools_per_turn"]):
        errors.append(
            f"Heavy tools would be used {heavy_tool_calls_used + heavy_in_batch} times this turn, above the configured max of {execution_config['max_heavy_tools_per_turn']}."
        )

    if planned_result_tokens > int(execution_config["tool_result_budget_tokens"]):
        errors.append(
            f"Planned tool-result budget ~{planned_result_tokens} tokens exceeds the configured batch budget of {execution_config['tool_result_budget_tokens']}."
        )

    if projected_next_input_tokens > int(execution_config["max_input_tokens"]) and not execution_config["auto_compact_session"]:
        errors.append(
            f"Projected next input ~{projected_next_input_tokens} tokens exceeds the configured max input of {execution_config['max_input_tokens']}."
        )

    return errors, {
        "planned_result_tokens": planned_result_tokens,
        "projected_next_input_tokens": projected_next_input_tokens,
        "heavy_in_batch": heavy_in_batch,
    }


def _tool_batch_rejection_message(
    batch_plan: dict[str, Any],
    errors: list[str],
    validation: dict[str, Any],
) -> str:
    compact_calls = [
        {
            "id": tool_call.get("id"),
            "tool_name": tool_call.get("tool_name"),
            "arguments": tool_call.get("arguments"),
            "reason": tool_call.get("reason"),
        }
        for tool_call in batch_plan.get("tool_calls", [])
    ]
    return "\n\n".join(
        part
        for part in (
            "Tool batch rejection.",
            _text_block("Execution", str(batch_plan.get("execution") or "sequential")),
            _text_block("Batch reason", str(batch_plan.get("batch_reason") or "")),
            _json_block("Planned tool calls", compact_calls),
            _json_block("Budget estimate", validation),
            _json_block("Errors", errors),
            "Continue the same turn. Submit a smaller batch, change execution order, or finish the turn.",
        )
        if part
    )


def _tool_batch_result_message(
    batch_plan: dict[str, Any],
    step_results: list[dict[str, Any]],
    validation: dict[str, Any],
    execution_config: dict[str, Any],
) -> str:
    tool_result_budget_chars = max(960, int(execution_config["tool_result_budget_tokens"]) * 4)
    per_step_chars = max(280, tool_result_budget_chars // max(1, len(step_results) + 1))
    step_blocks: list[str] = []

    for index, step in enumerate(step_results, start=1):
        compact_result = _compact_tool_result(step["tool_name"], step["result"])
        step_blocks.append(
            "\n".join(
                part
                for part in (
                    f"Step {index} ({step['id']}): {step['tool_name']}",
                    _text_block("Reason", step.get("reason") or ""),
                    _text_block("Cost tier", step.get("tier") or "unknown"),
                    _json_block("Arguments", step.get("arguments") or {}),
                    _render_result_block("Result", compact_result, max_chars=per_step_chars),
                )
                if part
            )
        )

    parts = [
        "Tool batch result.",
        _text_block("Execution", str(batch_plan.get("execution") or "sequential")),
        _text_block("Batch reason", str(batch_plan.get("batch_reason") or "")),
        _json_block("Batch budget", validation),
        *step_blocks,
    ]

    if validation.get("projected_next_input_tokens", 0) > int(execution_config["max_input_tokens"]):
        parts.append(
            "Automatic context compaction may be applied before the next model request to stay within the configured input budget."
        )

    parts.append("Continue the same turn. Either call another tool batch or finish the turn.")
    return "\n\n".join(part for part in parts if part)


def _increment_tool_usage_counts(tool_usage_counts: dict[str, int], tool_calls: list[dict[str, Any]]) -> int:
    heavy_calls_added = 0
    for tool_call in tool_calls:
        tool_name = str(tool_call.get("tool_name") or "").strip()
        if not tool_name:
            continue
        tool_usage_counts[tool_name] = tool_usage_counts.get(tool_name, 0) + 1
        if _AGENT_TOOL_SPECS.get(tool_name, {}).get("tier") == "heavy":
            heavy_calls_added += 1
    return heavy_calls_added


def _normalize_operations(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []

    normalized: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        op = item.get("op")
        target = item.get("target")
        if not isinstance(op, str) or not isinstance(target, dict):
            continue
        normalized.append(
            {
                "op": op,
                "target": target,
                "value": item.get("value", {}),
                "description": item.get("description", ""),
            }
        )
    return normalized


async def _load_selection_window(
    client: DocMcpClient,
    document_session_id: str,
    selection: dict[str, Any] | None,
) -> tuple[list[tuple[str, Any]], dict[str, Any] | None]:
    if not selection or not selection.get("blockId"):
        return [], None

    block_id = selection["blockId"]
    events = [_tool_started("get_context_window", sessionId=document_session_id, blockId=block_id)]
    context_window = await client.call_tool(
        "get_context_window",
        {
            "session_id": document_session_id,
            "block_id": block_id,
            "window_size": 2,
        },
    )
    events.append(_tool_finished("get_context_window", sessionId=document_session_id, blockId=block_id))
    return events, context_window if isinstance(context_window, dict) else None


async def _load_answer_context(
    client: DocMcpClient,
    document_session_id: str,
    question: str,
    *,
    phase: str | None = None,
) -> tuple[list[tuple[str, Any]], dict[str, Any]]:
    event_payload: dict[str, Any] = {"sessionId": document_session_id}
    if phase:
        event_payload["phase"] = phase

    events = [_tool_started("answer_about_document", **event_payload)]
    answer_context = await client.call_tool(
        "answer_about_document",
        {"session_id": document_session_id, "question": question},
    )
    answer_context = answer_context if isinstance(answer_context, dict) else {}
    events.append(
        _tool_finished(
            "answer_about_document",
            **event_payload,
            headingCount=len(answer_context.get("headings", [])),
            snippetCount=len(answer_context.get("relevant_snippets", [])),
        )
    )
    return events, answer_context


async def _locate_relevant_context(
    client: DocMcpClient,
    document_session_id: str,
    query: str,
    *,
    phase: str | None = None,
) -> tuple[list[tuple[str, Any]], dict[str, Any] | None]:
    event_payload: dict[str, Any] = {
        "sessionId": document_session_id,
        "query": query,
    }
    if phase:
        event_payload["phase"] = phase

    events = [_tool_started("locate_relevant_context", **event_payload)]
    relevant_context = await client.call_tool(
        "locate_relevant_context",
        {"session_id": document_session_id, "query": query},
    )
    events.append(
        _tool_finished(
            "locate_relevant_context",
            **event_payload,
            matchCount=relevant_context.get("match_count", 0) if isinstance(relevant_context, dict) else 0,
        )
    )
    return events, relevant_context if isinstance(relevant_context, dict) else None


async def _load_source_html(
    client: DocMcpClient,
    document_session_id: str,
    *,
    phase: str | None = None,
) -> tuple[list[tuple[str, Any]], str]:
    event_payload: dict[str, Any] = {"sessionId": document_session_id}
    if phase:
        event_payload["phase"] = phase

    events = [_tool_started("get_source_html", **event_payload)]
    source_html = await client.get_source_html(document_session_id)
    events.append(
        _tool_finished(
            "get_source_html",
            **event_payload,
            contentLength=len(source_html),
        )
    )
    return events, source_html


async def _load_context_windows_for_blocks(
    client: DocMcpClient,
    document_session_id: str,
    block_ids: list[str],
    *,
    phase: str,
) -> tuple[list[tuple[str, Any]], list[dict[str, Any]]]:
    events: list[tuple[str, Any]] = []
    windows: list[dict[str, Any]] = []

    for block_id in block_ids[:3]:
        events.append(_tool_started("get_context_window", sessionId=document_session_id, blockId=block_id, phase=phase))
        context_window = await client.call_tool(
            "get_context_window",
            {
                "session_id": document_session_id,
                "block_id": block_id,
                "window_size": 2,
            },
        )
        events.append(_tool_finished("get_context_window", sessionId=document_session_id, blockId=block_id, phase=phase))
        if isinstance(context_window, dict):
            windows.append({"block_id": block_id, "window": context_window})

    return events, windows


async def _execute_agent_tool_call(
    client: DocMcpClient,
    document_session_id: str,
    tool_name: str,
    arguments: dict[str, Any],
    *,
    prompt: str,
) -> tuple[list[tuple[str, Any]], Any]:
    normalized_tool_name = str(tool_name or "").strip()
    normalized_arguments = arguments if isinstance(arguments, dict) else {}
    event_payload: dict[str, Any] = {
        "sessionId": document_session_id,
        "phase": _AGENT_LOOP_PHASE,
    }

    if normalized_tool_name not in _AGENT_TOOL_NAMES:
        error_message = (
            f"Unsupported tool `{normalized_tool_name}`. Supported tools: "
            f"{', '.join(sorted(_AGENT_TOOL_NAMES))}."
        )
        return [
            _tool_started(normalized_tool_name or "unknown_tool", **event_payload),
            _tool_finished(normalized_tool_name or "unknown_tool", **event_payload, error=error_message),
        ], {"error": error_message}

    if normalized_tool_name == "get_session_summary":
        events = [_tool_started("get_session_summary", **event_payload)]
        summary = await client.get_session_summary(document_session_id)
        events.append(
            _tool_finished(
                "get_session_summary",
                **event_payload,
                state=summary.get("state"),
                currentRevisionId=summary.get("current_revision_id") or None,
            )
        )
        return events, summary

    if normalized_tool_name == "answer_about_document":
        question = str(normalized_arguments.get("question") or prompt).strip() or prompt
        return await _load_answer_context(
            client,
            document_session_id,
            question,
            phase=_AGENT_LOOP_PHASE,
        )

    if normalized_tool_name == "inspect_document":
        events = [_tool_started("inspect_document", **event_payload)]
        inspection = await client.call_tool(
            "inspect_document",
            {
                "session_id": document_session_id,
                "include_outline": _coerce_bool(normalized_arguments.get("include_outline"), True),
                "include_style_summary": _coerce_bool(normalized_arguments.get("include_style_summary"), True),
            },
        )
        inspection = inspection if isinstance(inspection, dict) else {"result": inspection}
        metadata = inspection.get("metadata") if isinstance(inspection, dict) else None
        section_count = metadata.get("section_count") if isinstance(metadata, dict) else None
        events.append(
            _tool_finished(
                "inspect_document",
                **event_payload,
                sectionCount=section_count,
            )
        )
        return events, inspection

    if normalized_tool_name == "locate_relevant_context":
        query = str(normalized_arguments.get("query") or "").strip()
        if not query:
            error_message = "Missing required argument `query` for locate_relevant_context."
            return [
                _tool_started("locate_relevant_context", **event_payload),
                _tool_finished("locate_relevant_context", **event_payload, error=error_message),
            ], {"error": error_message}
        return await _locate_relevant_context(
            client,
            document_session_id,
            query,
            phase=_AGENT_LOOP_PHASE,
        )

    if normalized_tool_name == "get_context_window":
        block_id = str(normalized_arguments.get("block_id") or "").strip()
        if not block_id:
            error_message = "Missing required argument `block_id` for get_context_window."
            return [
                _tool_started("get_context_window", **event_payload),
                _tool_finished("get_context_window", **event_payload, error=error_message),
            ], {"error": error_message}

        window_size = _coerce_window_size(normalized_arguments.get("window_size"), 2)
        window_event_payload = {**event_payload, "blockId": block_id}
        events = [_tool_started("get_context_window", **window_event_payload)]
        context_window = await client.call_tool(
            "get_context_window",
            {
                "session_id": document_session_id,
                "block_id": block_id,
                "window_size": window_size,
            },
        )
        events.append(_tool_finished("get_context_window", **window_event_payload))
        return events, context_window if isinstance(context_window, dict) else {"result": context_window}

    if normalized_tool_name == "get_source_html":
        return await _load_source_html(client, document_session_id, phase=_AGENT_LOOP_PHASE)

    if normalized_tool_name == "get_analysis_html":
        events = [_tool_started("get_analysis_html", **event_payload)]
        analysis_html = await client.get_analysis_html(document_session_id)
        events.append(
            _tool_finished(
                "get_analysis_html",
                **event_payload,
                contentLength=len(analysis_html),
            )
        )
        return events, analysis_html

    return [], {"error": f"Unhandled tool `{normalized_tool_name}`."}


async def _execute_agent_tool_batch(
    client: DocMcpClient,
    document_session_id: str,
    batch_plan: dict[str, Any],
    *,
    prompt: str,
) -> tuple[list[tuple[str, Any]], list[dict[str, Any]]]:
    execution = str(batch_plan.get("execution") or "sequential").strip().lower() or "sequential"
    tool_calls = batch_plan.get("tool_calls") if isinstance(batch_plan.get("tool_calls"), list) else []
    batch_event_payload = {
        "phase": _AGENT_LOOP_PHASE,
        "execution": execution,
        "toolCount": len(tool_calls),
    }

    events: list[tuple[str, Any]] = [_tool_started(_TOOL_BATCH_EVENT, **batch_event_payload)]
    step_results: list[dict[str, Any]] = []

    if execution == "parallel" and len(tool_calls) > 1:
        execution_results = await asyncio.gather(
            *[
                _execute_agent_tool_call(
                    client,
                    document_session_id,
                    str(tool_call.get("tool_name") or "").strip(),
                    tool_call.get("arguments") if isinstance(tool_call.get("arguments"), dict) else {},
                    prompt=prompt,
                )
                for tool_call in tool_calls
            ]
        )

        for tool_call, (tool_events, tool_result) in zip(tool_calls, execution_results, strict=False):
            events.extend(tool_events)
            step_results.append(
                {
                    "id": tool_call.get("id") or f"step_{len(step_results) + 1}",
                    "tool_name": str(tool_call.get("tool_name") or "").strip(),
                    "arguments": tool_call.get("arguments") if isinstance(tool_call.get("arguments"), dict) else {},
                    "reason": str(tool_call.get("reason") or "").strip(),
                    "tier": _AGENT_TOOL_SPECS.get(str(tool_call.get("tool_name") or "").strip(), {}).get("tier", "unknown"),
                    "result": tool_result,
                }
            )
    else:
        for tool_call in tool_calls:
            tool_name = str(tool_call.get("tool_name") or "").strip()
            tool_arguments = tool_call.get("arguments") if isinstance(tool_call.get("arguments"), dict) else {}
            tool_events, tool_result = await _execute_agent_tool_call(
                client,
                document_session_id,
                tool_name,
                tool_arguments,
                prompt=prompt,
            )
            events.extend(tool_events)
            step_results.append(
                {
                    "id": tool_call.get("id") or f"step_{len(step_results) + 1}",
                    "tool_name": tool_name,
                    "arguments": tool_arguments,
                    "reason": str(tool_call.get("reason") or "").strip(),
                    "tier": _AGENT_TOOL_SPECS.get(tool_name, {}).get("tier", "unknown"),
                    "result": tool_result,
                }
            )

    events.append(_tool_finished(_TOOL_BATCH_EVENT, **batch_event_payload, completedToolCount=len(step_results)))
    return events, step_results


def _dedupe_block_ids(*block_id_groups: list[str]) -> list[str]:
    block_ids: list[str] = []
    seen: set[str] = set()

    for group in block_id_groups:
        for block_id in group:
            normalized = str(block_id or "").strip()
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            block_ids.append(normalized)

    return block_ids


async def stream_turn(
    provider: BaseProvider,
    *,
    prompt: str,
    mode: str,
    document_session_id: str,
    base_revision_id: str | None,
    selection: dict[str, Any] | None,
    workspace_context: dict[str, Any] | None,
    history: list[dict[str, str]],
    execution_config: dict[str, Any] | None = None,
) -> AsyncIterator[tuple[str, Any]]:
    client = DocMcpClient()
    intent = mode
    execution_policy = _resolve_agent_execution_config(execution_config)

    log_core_event(
        "agent.turn.start",
        traceId=get_trace_id() or None,
        mode=mode,
        intent=intent,
        documentSessionId=document_session_id,
        baseRevisionId=base_revision_id,
        prompt=prompt,
        selection=selection,
        workspaceContext=workspace_context,
        executionConfig=execution_policy,
        history=history,
        providerClass=provider.__class__.__name__,
        providerModel=getattr(provider, "default_model", None),
    )

    if mode == "ask":
        ask_notices: list[dict[str, Any]] = []
        yield _tool_started("get_session_summary", sessionId=document_session_id)
        summary = await client.get_session_summary(document_session_id)
        yield _tool_finished(
            "get_session_summary",
            sessionId=document_session_id,
            state=summary.get("state"),
            currentRevisionId=summary.get("current_revision_id") or None,
        )

        current_revision_id = summary.get("current_revision_id") or None
        effective_base_revision_id = base_revision_id or current_revision_id or None

        if base_revision_id and current_revision_id and base_revision_id != current_revision_id:
            yield (
                "notice",
                {
                    "code": "stale_base_revision",
                    "message": (
                        f"baseRevisionId {base_revision_id} is behind the current revision "
                        f"{current_revision_id}. The backend will still stage against the caller's base revision "
                        "so doc-mcp can perform conflict detection correctly."
                    ),
                },
            )

        selection_events, selection_window = await _load_selection_window(client, document_session_id, selection)
        for event in selection_events:
            yield event

        answer_events, answer_context = await _load_answer_context(client, document_session_id, prompt)
        for event in answer_events:
            yield event

        analysis_block = ""
        if not _has_answer_snippets(answer_context):
            yield _tool_started("get_analysis_html", sessionId=document_session_id)
            analysis_html = await client.get_analysis_html(document_session_id)
            yield _tool_finished(
                "get_analysis_html",
                sessionId=document_session_id,
                contentLength=len(analysis_html),
            )
            analysis_block = _text_block("Document analysis HTML excerpt", _html_excerpt(analysis_html))

        context_blocks = [
            _json_block("Session summary", summary),
            _json_block("Document answer context", answer_context),
            _selection_block(selection),
            _workspace_block(workspace_context),
        ]
        if selection_window:
            context_blocks.append(_json_block("Selection window", selection_window))
        if analysis_block:
            context_blocks.append(analysis_block)

        messages = _build_messages(ASK_SYSTEM_PROMPT, prompt, history, context_blocks)
        messages, compaction_info = _compact_messages_to_fit_budget(messages, execution_policy)
        if compaction_info:
            yield _tool_started(
                _CONTEXT_COMPACTION_TOOL,
                phase="compose_answer",
                originalEstimatedInputTokens=compaction_info["original_estimated_input_tokens"],
                budgetTokens=execution_policy["max_input_tokens"],
            )
            yield _tool_finished(
                _CONTEXT_COMPACTION_TOOL,
                phase="compose_answer",
                finalEstimatedInputTokens=compaction_info["final_estimated_input_tokens"],
                summarizedMessageCount=compaction_info["summarized_message_count"],
                budgetTokens=execution_policy["max_input_tokens"],
            )
            ask_notices.append(
                {
                    "code": "context_compacted",
                    "message": "Session context was compacted automatically to stay within the configured input budget.",
                }
            )
        log_core_event(
            "agent.llm.request",
            phase="compose_answer",
            mode=mode,
            documentSessionId=document_session_id,
            providerClass=provider.__class__.__name__,
            providerModel=getattr(provider, "default_model", None),
            messages=messages,
        )
        llm_request_index = 1
        llm_request_started = _build_llm_request_metrics(
            request_index=llm_request_index,
            phase="compose_answer",
            provider=provider,
            messages=messages,
            output_text="",
        )
        llm_request_started["maxInputTokens"] = execution_policy["max_input_tokens"]
        if compaction_info:
            llm_request_started["compactedInput"] = True
        yield _tool_started("llm_inference", **llm_request_started)
        assistant_chunks: list[str] = []
        async for chunk in provider.stream_chat(messages):
            if not chunk:
                continue
            assistant_chunks.append(chunk)
            yield "assistant_delta", chunk
        assistant_text = "".join(assistant_chunks)
        log_core_event(
            "agent.llm.response",
            phase="compose_answer",
            mode=mode,
            documentSessionId=document_session_id,
            output=assistant_text,
        )
        llm_request = _build_llm_request_metrics(
            request_index=llm_request_index,
            phase="compose_answer",
            provider=provider,
            messages=messages,
            output_text=assistant_text,
        )
        llm_request["maxInputTokens"] = execution_policy["max_input_tokens"]
        if compaction_info:
            llm_request["compactedInput"] = True
        yield _tool_finished(
            "llm_inference",
            chunkCount=len(assistant_chunks),
            charCount=len(assistant_text),
            **llm_request,
        )
        yield (
            "done",
            {
                "message": assistant_text,
                "mode": mode,
                "intent": intent,
                "resultType": "answer",
                "documentSessionId": document_session_id,
                "baseRevisionId": effective_base_revision_id or None,
                "revisionId": None,
                "status": "completed",
                "notices": ask_notices or None,
                "usage": _summarize_llm_requests([llm_request]),
            },
        )
        return

    llm_requests: list[dict[str, Any]] = []
    loop_transcript: list[dict[str, str]] = []
    notices: list[dict[str, Any]] = []
    current_revision_id: str | None = None
    tool_usage_counts: dict[str, int] = {}
    heavy_tool_calls_used = 0

    for llm_request_index in range(1, _MAX_AGENT_LOOP_STEPS + 1):
        system_prompt = _build_agent_system_prompt(execution_policy)
        planning_messages = _build_agent_loop_messages(
            system_prompt,
            prompt,
            history,
            selection,
            workspace_context,
            loop_transcript,
        )
        planning_messages, compaction_info = _compact_messages_to_fit_budget(planning_messages, execution_policy)
        if compaction_info:
            yield _tool_started(
                _CONTEXT_COMPACTION_TOOL,
                phase=_AGENT_LOOP_PHASE,
                originalEstimatedInputTokens=compaction_info["original_estimated_input_tokens"],
                budgetTokens=execution_policy["max_input_tokens"],
            )
            yield _tool_finished(
                _CONTEXT_COMPACTION_TOOL,
                phase=_AGENT_LOOP_PHASE,
                finalEstimatedInputTokens=compaction_info["final_estimated_input_tokens"],
                summarizedMessageCount=compaction_info["summarized_message_count"],
                budgetTokens=execution_policy["max_input_tokens"],
            )
            if not any(notice.get("code") == "context_compacted" for notice in notices):
                notices.append(
                    {
                        "code": "context_compacted",
                        "message": "Session context was compacted automatically to stay within the configured input budget.",
                    }
                )
        log_core_event(
            "agent.llm.request",
            phase=_AGENT_LOOP_PHASE,
            mode=mode,
            documentSessionId=document_session_id,
            providerClass=provider.__class__.__name__,
            providerModel=getattr(provider, "default_model", None),
            messages=planning_messages,
        )
        llm_request_started = _build_llm_request_metrics(
            request_index=llm_request_index,
            phase=_AGENT_LOOP_PHASE,
            provider=provider,
            messages=planning_messages,
            output_text="",
        )
        llm_request_started["maxInputTokens"] = execution_policy["max_input_tokens"]
        if compaction_info:
            llm_request_started["compactedInput"] = True
        yield _tool_started("llm_inference", **llm_request_started)

        model_chunks: list[str] = []
        async for chunk in provider.stream_chat(planning_messages):
            if chunk:
                model_chunks.append(chunk)
        model_output = "".join(model_chunks)
        log_core_event(
            "agent.llm.response",
            phase=_AGENT_LOOP_PHASE,
            mode=mode,
            documentSessionId=document_session_id,
            output=model_output,
        )
        llm_request = _build_llm_request_metrics(
            request_index=llm_request_index,
            phase=_AGENT_LOOP_PHASE,
            provider=provider,
            messages=planning_messages,
            output_text=model_output,
        )
        llm_request["maxInputTokens"] = execution_policy["max_input_tokens"]
        if compaction_info:
            llm_request["compactedInput"] = True
        llm_requests.append(llm_request)
        yield _tool_finished(
            "llm_inference",
            chunkCount=len(model_chunks),
            charCount=len(model_output),
            **llm_request,
        )

        action_payloads = _extract_json_objects(model_output)
        action_payload = _select_agent_action(action_payloads)
        if not action_payload:
            error_message = "The assistant returned an invalid internal response. Please try again."
            notices.append({
                "code": "agent_invalid_json",
                "message": error_message,
            })
            yield (
                "done",
                {
                    "message": error_message,
                    "mode": mode,
                    "intent": intent,
                    "resultType": "answer",
                    "documentSessionId": document_session_id,
                    "baseRevisionId": base_revision_id or current_revision_id or None,
                    "revisionId": None,
                    "status": "failed",
                    "notices": notices or None,
                    "usage": _summarize_llm_requests(llm_requests),
                },
            )
            return

        if len(action_payloads) > 1:
            log_core_event(
                "agent.llm.multiple_actions",
                mode=mode,
                documentSessionId=document_session_id,
                actionCount=len(action_payloads),
                chosenAction=str(action_payload.get("action") or ""),
            )

        action = str(action_payload.get("action") or "final").strip().lower()
        if action not in {"call_tool", "call_tools", "final"}:
            error_message = "The assistant returned an invalid internal response. Please try again."
            notices.append({
                "code": "agent_invalid_action",
                "message": error_message,
            })
            yield (
                "done",
                {
                    "message": error_message,
                    "mode": mode,
                    "intent": intent,
                    "resultType": "answer",
                    "documentSessionId": document_session_id,
                    "baseRevisionId": base_revision_id or current_revision_id or None,
                    "revisionId": None,
                    "status": "failed",
                    "notices": notices or None,
                    "usage": _summarize_llm_requests(llm_requests),
                },
            )
            return

        if action in {"call_tool", "call_tools"}:
            batch_plan = _normalize_tool_batch_plan(action_payload)
            validation_errors, validation = _validate_tool_batch(
                batch_plan,
                execution_policy,
                tool_usage_counts,
                heavy_tool_calls_used,
                int(llm_request_started["estimatedInputTokens"]),
            )
            loop_transcript.append({"role": "assistant", "content": json.dumps(action_payload, ensure_ascii=False)})
            if validation_errors:
                log_core_event(
                    "agent.tool_batch.rejected",
                    mode=mode,
                    documentSessionId=document_session_id,
                    batchPlan=batch_plan,
                    errors=validation_errors,
                    validation=validation,
                )
                loop_transcript.append(
                    {
                        "role": "user",
                        "content": _tool_batch_rejection_message(batch_plan, validation_errors, validation),
                    }
                )
                continue

            tool_events, step_results = await _execute_agent_tool_batch(
                client,
                document_session_id,
                batch_plan,
                prompt=prompt,
            )
            for event in tool_events:
                yield event

            heavy_tool_calls_used += _increment_tool_usage_counts(tool_usage_counts, batch_plan["tool_calls"])
            for step in step_results:
                if step["tool_name"] == "get_session_summary" and isinstance(step["result"], dict):
                    current_revision_id = str(step["result"].get("current_revision_id") or "").strip() or current_revision_id

            log_core_event(
                "agent.tool_batch.completed",
                mode=mode,
                documentSessionId=document_session_id,
                batchPlan=batch_plan,
                validation=validation,
            )
            loop_transcript.append(
                {
                    "role": "user",
                    "content": _tool_batch_result_message(batch_plan, step_results, validation, execution_policy),
                }
            )
            continue

        result_type = str(action_payload.get("result_type") or "clarify").strip().lower()
        assistant_message = str(action_payload.get("assistant_message") or model_output).strip()
        operations = _normalize_operations(action_payload.get("operations"))
        revision_summary = str(action_payload.get("summary") or prompt).strip()

        if isinstance(action_payload.get("risks"), list) and action_payload["risks"]:
            notices.append({"code": "agent_risks", "items": action_payload["risks"]})

        if result_type != "propose_edit" or not operations or not _operations_have_explicit_targets(operations):
            if result_type == "propose_edit" and (not operations or not _operations_have_explicit_targets(operations)):
                notices.append({
                    "code": "agent_missing_targets",
                    "items": ["The model proposed an edit without explicit document target ids, so no revision was staged."],
                })

            final_result_type = "clarify" if result_type == "clarify" else "answer"
            log_core_event(
                "agent.turn.completed",
                mode=mode,
                intent=intent,
                documentSessionId=document_session_id,
                revisionId=None,
                message=assistant_message,
                notices=notices,
            )
            for chunk in _chunk_text(assistant_message):
                yield "assistant_delta", chunk
            yield (
                "done",
                {
                    "message": assistant_message,
                    "mode": mode,
                    "intent": intent,
                    "resultType": final_result_type,
                    "documentSessionId": document_session_id,
                    "baseRevisionId": base_revision_id or current_revision_id or None,
                    "revisionId": None,
                    "status": "completed",
                    "notices": notices or None,
                    "usage": _summarize_llm_requests(llm_requests),
                },
            )
            return

        yield _tool_started("get_session_summary", sessionId=document_session_id, phase="stage_revision")
        summary = await client.get_session_summary(document_session_id)
        yield _tool_finished(
            "get_session_summary",
            sessionId=document_session_id,
            phase="stage_revision",
            state=summary.get("state"),
            currentRevisionId=summary.get("current_revision_id") or None,
        )

        current_revision_id = summary.get("current_revision_id") or None
        effective_base_revision_id = base_revision_id or current_revision_id or None

        if base_revision_id and current_revision_id and base_revision_id != current_revision_id:
            yield (
                "notice",
                {
                    "code": "stale_base_revision",
                    "message": (
                        f"baseRevisionId {base_revision_id} is behind the current revision "
                        f"{current_revision_id}. The backend will still stage against the caller's base revision "
                        "so doc-mcp can perform conflict detection correctly."
                    ),
                },
            )

        proposal_payload = {
            "session_id": document_session_id,
            "operations": operations,
            "summary": revision_summary,
            "author": "agent",
        }
        if effective_base_revision_id:
            proposal_payload["base_revision_id"] = effective_base_revision_id
        log_core_event(
            "agent.proposal.request",
            documentSessionId=document_session_id,
            proposal=proposal_payload,
        )
        yield _tool_started(
            "propose_document_edit",
            sessionId=document_session_id,
            operationCount=len(operations),
        )
        proposal = await client.call_tool("propose_document_edit", proposal_payload)
        proposal = proposal if isinstance(proposal, dict) else {"raw": proposal}
        log_core_event(
            "agent.proposal.response",
            documentSessionId=document_session_id,
            proposal=proposal,
        )
        yield _tool_finished(
            "propose_document_edit",
            sessionId=document_session_id,
            revisionId=proposal.get("revision_id"),
            status=proposal.get("status"),
        )

        revision_id = proposal.get("revision_id")
        review: dict[str, Any] | None = None
        if revision_id:
            yield _tool_started("review_pending_revision", sessionId=document_session_id, revisionId=revision_id)
            review = await client.review_pending_revision(document_session_id, revision_id)
            log_core_event(
                "agent.review.response",
                documentSessionId=document_session_id,
                revisionId=revision_id,
                review=review,
            )
            yield _tool_finished("review_pending_revision", sessionId=document_session_id, revisionId=revision_id)

        validation = proposal.get("validation") if isinstance(proposal, dict) else None
        if isinstance(validation, dict) and validation.get("errors"):
            notices.append({"code": "validation_errors", "items": validation.get("errors")})
        if isinstance(validation, dict) and validation.get("warnings"):
            notices.append({"code": "validation_warnings", "items": validation.get("warnings")})

        final_message = assistant_message
        log_core_event(
            "agent.turn.completed",
            mode=mode,
            intent=intent,
            documentSessionId=document_session_id,
            revisionId=revision_id,
            message=final_message,
            notices=notices,
        )
        for chunk in _chunk_text(final_message):
            yield "assistant_delta", chunk

        yield (
            "done",
            {
                "message": final_message,
                "mode": mode,
                "intent": intent,
                "resultType": "revision_staged" if revision_id else "answer",
                "documentSessionId": document_session_id,
                "baseRevisionId": effective_base_revision_id or None,
                "revisionId": revision_id,
                "status": "completed",
                "proposal": proposal,
                "review": review,
                "notices": notices or None,
                "usage": _summarize_llm_requests(llm_requests),
            },
        )
        return

    loop_limit_message = "The assistant needs a more specific request before it can continue."
    notices.append({
        "code": "agent_loop_limit",
        "items": [f"The agent reached the maximum of {_MAX_AGENT_LOOP_STEPS} reasoning steps."],
    })
    for chunk in _chunk_text(loop_limit_message):
        yield "assistant_delta", chunk
    yield (
        "done",
        {
            "message": loop_limit_message,
            "mode": mode,
            "intent": intent,
            "resultType": "clarify",
            "documentSessionId": document_session_id,
            "baseRevisionId": base_revision_id or current_revision_id or None,
            "revisionId": None,
            "status": "completed",
            "notices": notices or None,
            "usage": _summarize_llm_requests(llm_requests),
        },
    )