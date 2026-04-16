import json
import math
import re
from typing import Any, AsyncIterator

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

AGENT_JSON_SYSTEM_PROMPT = """You are DocPilot running in AGENT mode.

Return JSON only. No markdown fences. No prose before or after the JSON.

Schema:
{
  "decision": "clarify" | "answer" | "propose_edit",
  "assistant_message": "string",
  "summary": "short revision summary",
  "operations": [
    {
      "op": "REPLACE_TEXT_RANGE" | "INSERT_TEXT_AT" | "DELETE_TEXT_RANGE" | "APPLY_STYLE" | "APPLY_INLINE_FORMAT" | "SET_HEADING_LEVEL" | "CREATE_BLOCK" | "UPDATE_CELL_CONTENT" | "CHANGE_LIST_TYPE" | "CHANGE_LIST_LEVEL",
      "target": {
        "block_id": "string",
        "start": 0,
        "end": 10,
        "table_id": "string",
        "row_id": "string",
        "cell_id": "string",
        "cell_logical_address": "R1C1"
      },
      "value": {},
      "description": "string"
    }
  ],
  "risks": ["string"],
  "needs_review": true
}

Rules:
- Use decision="clarify" when the request is ambiguous or unsafe to execute.
- Use decision="answer" when no document mutation is required.
- In AGENT mode, decide between answering and staging an edit; prefer decision="propose_edit" when the user likely expects the document to change.
- Use decision="propose_edit" only when you can target explicit block ids or table cells from the supplied context.
- If resolved targets, relevant matches, or selection windows already include explicit block ids, use them directly and do not ask the user to provide block ids again.
- Use the supplied source HTML excerpt and retrieved tool context to ground your decision before asking the user for more detail.
- Never invent block ids, table ids, row ids, or cell ids.
- Prefer the smallest operation set that satisfies the request.
- Use snake_case target keys exactly as shown above.
- For REPLACE_TEXT_RANGE and UPDATE_CELL_CONTENT, put the replacement text in value.text.
- For APPLY_STYLE, use value.style_id.
- For SET_HEADING_LEVEL or CHANGE_LIST_LEVEL, use value.level.
- For CREATE_BLOCK, use value.type and value.text at minimum.
- assistant_message must summarize what will be staged for review, or ask the clarifying question.
- Reply in the same language as the user inside assistant_message.
"""


def _build_messages(
    system_prompt: str,
    prompt: str,
    history: list[dict[str, str]],
    context_blocks: list[str],
) -> list[dict[str, str]]:
    messages: list[dict[str, str]] = [{"role": "system", "content": system_prompt}]

    for message in history:
        role = message.get("role")
        content = message.get("content", "")
        if role in {"system", "user", "assistant"}:
            messages.append({"role": role, "content": content})

    user_content = "\n\n".join(block for block in context_blocks if block)
    user_content = f"{user_content}\n\nUser request:\n{prompt}" if user_content else prompt
    messages.append({"role": "user", "content": user_content})
    return messages


def _json_block(label: str, payload: Any) -> str:
    return f"{label}:\n{json.dumps(payload, ensure_ascii=False, indent=2)}"


def _text_block(label: str, text: str) -> str:
    stripped = text.strip()
    if not stripped:
        return ""
    return f"{label}:\n{stripped}"


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
    if not text:
        return None

    candidate = text.strip()
    fence_match = _JSON_FENCE_PATTERN.search(candidate)
    if fence_match:
        candidate = fence_match.group(1)

    try:
        parsed = json.loads(candidate)
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        pass

    first_brace = candidate.find("{")
    last_brace = candidate.rfind("}")
    if first_brace >= 0 and last_brace > first_brace:
        try:
            parsed = json.loads(candidate[first_brace:last_brace + 1])
            return parsed if isinstance(parsed, dict) else None
        except json.JSONDecodeError:
            return None
    return None


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
) -> AsyncIterator[tuple[str, Any]]:
    client = DocMcpClient()
    intent = mode

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
        history=history,
        providerClass=provider.__class__.__name__,
        providerModel=getattr(provider, "default_model", None),
    )

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

    if mode == "ask":
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
        yield _tool_started(
            "llm_inference",
            phase="compose_answer",
            requestIndex=llm_request_index,
            provider=_provider_name(provider),
            providerDisplayName=_provider_display_name(provider),
            model=_resolved_model_name(provider),
        )
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
                "usage": _summarize_llm_requests([llm_request]),
            },
        )
        return

    answer_events, answer_context = await _load_answer_context(
        client,
        document_session_id,
        prompt,
        phase="answer_context",
    )
    for event in answer_events:
        yield event

    yield _tool_started("inspect_document", sessionId=document_session_id)
    inspection = await client.call_tool(
        "inspect_document",
        {
            "session_id": document_session_id,
            "include_outline": True,
            "include_style_summary": True,
        },
    )
    yield _tool_finished(
        "inspect_document",
        sessionId=document_session_id,
        sectionCount=summary.get("section_count", 0),
    )

    source_events, source_html = await _load_source_html(
        client,
        document_session_id,
        phase="fidelity_source",
    )
    for event in source_events:
        yield event

    locate_events, relevant_context = await _locate_relevant_context(
        client,
        document_session_id,
        prompt,
        phase="prompt",
    )
    for event in locate_events:
        yield event

    prompt_match_block_ids = _collect_match_block_ids(relevant_context)
    prompt_window_events, prompt_match_windows = await _load_context_windows_for_blocks(
        client,
        document_session_id,
        prompt_match_block_ids,
        phase="prompt_matches",
    )
    for event in prompt_window_events:
        yield event

    resolved_target_contexts: list[dict[str, Any]] = []
    resolved_target_windows: list[dict[str, Any]] = []
    candidate_queries = _extract_edit_search_candidates(prompt)
    for candidate in candidate_queries:
        target_events, target_context = await _locate_relevant_context(
            client,
            document_session_id,
            candidate["query"],
            phase=candidate["label"],
        )
        for event in target_events:
            yield event

        if not _has_relevant_matches(target_context):
            continue

        resolved_target_contexts.append(
            {
                "label": candidate["label"],
                "query": candidate["query"],
                "result": target_context,
            }
        )

    resolved_block_ids = _dedupe_block_ids(
        *[_collect_match_block_ids(context_entry.get("result")) for context_entry in resolved_target_contexts]
    )
    context_window_events, resolved_target_windows = await _load_context_windows_for_blocks(
        client,
        document_session_id,
        resolved_block_ids,
        phase="resolved_targets",
    )
    for event in context_window_events:
        yield event

    source_html_block = _text_block("Document source HTML excerpt", _html_excerpt(source_html))

    context_blocks = [
        _json_block("Session summary", summary),
        _json_block("Document answer context", answer_context),
        _json_block("Document inspection", inspection),
        _json_block("Relevant context", relevant_context),
        _selection_block(selection),
        _workspace_block(workspace_context),
    ]
    if source_html_block:
        context_blocks.append(source_html_block)
    if selection_window:
        context_blocks.append(_json_block("Selection window", selection_window))
    if prompt_match_windows:
        context_blocks.append(_json_block("Prompt match windows", prompt_match_windows))
    if resolved_target_contexts:
        context_blocks.append(_json_block("Resolved edit targets", resolved_target_contexts))
    if resolved_target_windows:
        context_blocks.append(_json_block("Resolved target windows", resolved_target_windows))

    planning_messages = _build_messages(AGENT_JSON_SYSTEM_PROMPT, prompt, history, context_blocks)
    log_core_event(
        "agent.llm.request",
        phase="plan_revision",
        mode=mode,
        documentSessionId=document_session_id,
        providerClass=provider.__class__.__name__,
        providerModel=getattr(provider, "default_model", None),
        messages=planning_messages,
    )
    llm_request_index = 1
    yield _tool_started(
        "llm_inference",
        phase="plan_revision",
        requestIndex=llm_request_index,
        provider=_provider_name(provider),
        providerDisplayName=_provider_display_name(provider),
        model=_resolved_model_name(provider),
    )
    model_chunks: list[str] = []
    async for chunk in provider.stream_chat(planning_messages):
        if chunk:
            model_chunks.append(chunk)
    model_output = "".join(model_chunks)
    log_core_event(
        "agent.llm.response",
        phase="plan_revision",
        mode=mode,
        documentSessionId=document_session_id,
        output=model_output,
    )
    llm_request = _build_llm_request_metrics(
        request_index=llm_request_index,
        phase="plan_revision",
        provider=provider,
        messages=planning_messages,
        output_text=model_output,
    )
    yield _tool_finished(
        "llm_inference",
        chunkCount=len(model_chunks),
        charCount=len(model_output),
        **llm_request,
    )
    plan = _extract_json_object(model_output) or {}

    decision = str(plan.get("decision") or "clarify")
    assistant_message = str(plan.get("assistant_message") or model_output).strip()
    operations = _normalize_operations(plan.get("operations"))
    revision_summary = str(plan.get("summary") or prompt).strip()
    notices: list[dict[str, Any]] = []

    if decision != "propose_edit" or not operations:
        for chunk in _chunk_text(assistant_message):
            yield "assistant_delta", chunk
        if isinstance(plan.get("risks"), list) and plan["risks"]:
            notices.append({"code": "agent_risks", "items": plan["risks"]})
        yield (
            "done",
            {
                "message": assistant_message,
                "mode": mode,
                "intent": intent,
                "resultType": "clarify" if decision == "clarify" else "answer",
                "documentSessionId": document_session_id,
                "baseRevisionId": effective_base_revision_id or None,
                "revisionId": None,
                "status": "completed",
                "notices": notices or None,
                "usage": _summarize_llm_requests([llm_request]),
            },
        )
        return

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
    if isinstance(plan.get("risks"), list) and plan["risks"]:
        notices.append({"code": "agent_risks", "items": plan["risks"]})

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
            "usage": _summarize_llm_requests([llm_request]),
        },
    )