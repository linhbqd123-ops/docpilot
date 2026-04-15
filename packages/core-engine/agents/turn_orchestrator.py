import json
import re
from typing import Any, AsyncIterator

from providers.base import BaseProvider
from services.doc_mcp_client import DocMcpClient

_EDIT_INTENT_PATTERN = re.compile(
    r"\b(edit|rewrite|reword|rephrase|translate|format|fix|correct|update|modify|change|"
    r"delete|remove|insert|add|replace|polish|shorten|expand|restructure|improve|convert)\b",
    re.IGNORECASE,
)
_JSON_FENCE_PATTERN = re.compile(r"```json\s*(\{.*?\})\s*```", re.DOTALL | re.IGNORECASE)

ASK_SYSTEM_PROMPT = """You are DocPilot running in ASK mode.

Rules:
- ASK mode is read-only. Never say you changed the document.
- If the user asks for edits, explain that ASK mode cannot mutate the document and that they should switch to AGENT mode.
- Use only the supplied document context.
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
- Use decision="propose_edit" only when you can target explicit block ids or table cells from the supplied context.
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


def classify_intent(prompt: str) -> str:
    if _EDIT_INTENT_PATTERN.search(prompt or ""):
        return "edit"
    return "question"


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
    intent = classify_intent(prompt)

    yield _tool_started("get_session_summary", sessionId=document_session_id)
    summary = await client.get_session_summary(document_session_id)
    yield _tool_finished(
        "get_session_summary",
        sessionId=document_session_id,
        state=summary.get("state"),
        currentRevisionId=summary.get("current_revision_id") or None,
    )

    current_revision_id = summary.get("current_revision_id") or None
    effective_base_revision_id = base_revision_id or current_revision_id or ""

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

    if mode == "ask" or intent == "question":
        yield _tool_started("answer_about_document", sessionId=document_session_id)
        answer_context = await client.call_tool(
            "answer_about_document",
            {"session_id": document_session_id, "question": prompt},
        )
        yield _tool_finished(
            "answer_about_document",
            sessionId=document_session_id,
            headingCount=len(answer_context.get("headings", [])) if isinstance(answer_context, dict) else 0,
            snippetCount=len(answer_context.get("relevant_snippets", [])) if isinstance(answer_context, dict) else 0,
        )

        context_blocks = [
            _json_block("Session summary", summary),
            _json_block("Document answer context", answer_context),
            _selection_block(selection),
            _workspace_block(workspace_context),
        ]
        if selection_window:
            context_blocks.append(_json_block("Selection window", selection_window))

        messages = _build_messages(ASK_SYSTEM_PROMPT, prompt, history, context_blocks)
        assistant_text = await provider.chat(messages)
        for chunk in _chunk_text(assistant_text):
            yield "assistant_delta", chunk
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
            },
        )
        return

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

    yield _tool_started("locate_relevant_context", sessionId=document_session_id)
    relevant_context = await client.call_tool(
        "locate_relevant_context",
        {"session_id": document_session_id, "query": prompt},
    )
    yield _tool_finished(
        "locate_relevant_context",
        sessionId=document_session_id,
        matchCount=relevant_context.get("match_count", 0) if isinstance(relevant_context, dict) else 0,
    )

    context_blocks = [
        _json_block("Session summary", summary),
        _json_block("Document inspection", inspection),
        _json_block("Relevant context", relevant_context),
        _selection_block(selection),
        _workspace_block(workspace_context),
    ]
    if selection_window:
        context_blocks.append(_json_block("Selection window", selection_window))

    planning_messages = _build_messages(AGENT_JSON_SYSTEM_PROMPT, prompt, history, context_blocks)
    model_output = await provider.chat(planning_messages)
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
            },
        )
        return

    proposal_payload = {
        "session_id": document_session_id,
        "base_revision_id": effective_base_revision_id,
        "operations": operations,
        "summary": revision_summary,
        "author": "agent",
    }
    yield _tool_started(
        "propose_document_edit",
        sessionId=document_session_id,
        operationCount=len(operations),
    )
    proposal = await client.call_tool("propose_document_edit", proposal_payload)
    proposal = proposal if isinstance(proposal, dict) else {"raw": proposal}
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
        yield _tool_finished("review_pending_revision", sessionId=document_session_id, revisionId=revision_id)

    validation = proposal.get("validation") if isinstance(proposal, dict) else None
    if isinstance(validation, dict) and validation.get("errors"):
        notices.append({"code": "validation_errors", "items": validation.get("errors")})
    if isinstance(validation, dict) and validation.get("warnings"):
        notices.append({"code": "validation_warnings", "items": validation.get("warnings")})
    if isinstance(plan.get("risks"), list) and plan["risks"]:
        notices.append({"code": "agent_risks", "items": plan["risks"]})

    final_message = assistant_message
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
        },
    )