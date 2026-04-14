"""
Edit agent for DocPilot Core Engine.

Design principles
─────────────────
1. Tag-based structured output  (<DOCUMENT>…</DOCUMENT>) works universally across
   every LLM provider without requiring JSON schema support. Anthropic uses the
   same convention internally in their own products.

2. Robust extraction with multiple fallback patterns handles common model quirks
   (lowercase tags, markdown fences, unclosed tags in truncated responses).

3. Auto-retry  When extraction fails but the model's text clearly describes
   document changes, a non-streaming correction pass re-asks for the missing
   <DOCUMENT> block — up to MAX_RETRIES times. The chat stream has already been
   sent to the frontend so UX is unaffected; the corrected HTML arrives as a
   final event.

4. Prompt-enforced format  The system prompt repeats the <DOCUMENT> tag rule in
   multiple phrasings so weaker models (Groq fast models, small Ollama models)
   are less likely to forget it.
"""
import logging
import re
from typing import AsyncIterator

from config import settings
from providers.base import BaseProvider

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

MAX_RETRIES = 2
DOCUMENT_OPEN = "<DOCUMENT>"
DOCUMENT_CLOSE = "</DOCUMENT>"
LOOKAHEAD = len(DOCUMENT_OPEN) - 1

# Keyword pattern: response text suggests edits were made
_EDIT_SIGNALS = re.compile(
    r"\b(updated|rewritten|revised|translated|reformatted|changed|modified|"
    r"moved|added|removed|replaced|rewrote|corrected|improved|restructured|"
    r"converted|formatted|edited|here.{0,10}(is|are) the)\b",
    re.IGNORECASE,
)

# ---------------------------------------------------------------------------
# System prompt
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = """\
You are DocPilot, an expert AI document editor. You help users edit, rewrite, \
translate, format, summarise, and improve documents.

You are given the current document in HTML. Follow these rules without exception:

RULE 1 — RESPONSE STRUCTURE
  • If the user requests any document change (edit, rewrite, translate, format,
    insert, delete, etc.): first give a brief explanation, then output the
    COMPLETE updated document HTML wrapped in <DOCUMENT></DOCUMENT> tags.
  • If the user asks a question or requests analysis only (no edits): reply
    conversationally, no <DOCUMENT> block needed.

RULE 2 — DOCUMENT BLOCK FORMAT
  • The <DOCUMENT> tag MUST be on its own line.
  • Always output the FULL document inside <DOCUMENT></DOCUMENT>, not just
    the changed excerpt.
  • Do NOT use markdown code fences (``` or ```html) — only <DOCUMENT></DOCUMENT>.
  • The closing </DOCUMENT> tag MUST appear at the very end.

RULE 3 — CONTENT FIDELITY
  • Preserve all HTML tags, attributes, classes, and inline styles unless
    specifically asked to change formatting.
  • Only modify the sections the user requested.
  • Ensure the output HTML is valid and well-formed.

EXAMPLE of correct format when making changes:
───────────────────────────────────────────────
I've rewritten section 2 in plain English while preserving the structure.

<DOCUMENT>
<h1>Title</h1>
<p>Updated paragraph text here.</p>
</DOCUMENT>
───────────────────────────────────────────────
"""

RETRY_PROMPT = """\
Your previous response described document changes but the updated document was \
not wrapped in <DOCUMENT></DOCUMENT> tags as required.

Please output ONLY the complete updated document HTML now, wrapped correctly:

<DOCUMENT>
[complete updated HTML here]
</DOCUMENT>

Do not include any other text outside the tags.\
"""

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _truncate(html: str) -> str:
    limit = settings.max_document_html_chars
    if len(html) <= limit:
        return html
    return html[:limit] + "\n<!-- [document truncated — content exceeds display limit] -->"


def build_messages(
    prompt: str,
    document_html: str,
    history: list[dict],
) -> list[dict]:
    system_content = SYSTEM_PROMPT
    if document_html.strip():
        system_content += (
            f"\n\n<CURRENT_DOCUMENT>\n{_truncate(document_html)}\n</CURRENT_DOCUMENT>"
        )

    messages: list[dict] = [{"role": "system", "content": system_content}]

    for msg in history:
        if msg.get("role") in ("user", "assistant"):
            messages.append({"role": msg["role"], "content": msg["content"]})

    messages.append({"role": "user", "content": prompt})
    return messages


def extract_document_block(text: str) -> str | None:
    """
    Try to extract the updated document HTML from the model's full response.

    Precedence of extraction strategies (most to least reliable):
      1. Canonical  <DOCUMENT>…</DOCUMENT>  (case-insensitive)
      2. Last occurrence, in case model produced multiple blocks
      3. Markdown HTML code fence  ```html…```
      4. Unclosed tag  <DOCUMENT>…EOF  (truncated long response)
    """
    # Strategy 1 & 2: canonical tags, take the LAST match (most complete edit)
    matches = list(re.finditer(r"<DOCUMENT>(.*?)</DOCUMENT>", text, re.DOTALL | re.IGNORECASE))
    if matches:
        return matches[-1].group(1).strip()

    # Strategy 3: ```html … ``` code fence containing HTML content
    fence = re.search(
        r"```html?\s*((?:<!DOCTYPE|<html|<body|<div|<p|<h[1-6]|<ul|<ol|<table).*?)```",
        text,
        re.DOTALL | re.IGNORECASE,
    )
    if fence:
        return fence.group(1).strip()

    # Strategy 4: unclosed <DOCUMENT> tag (model cut off before writing </DOCUMENT>)
    partial = re.search(r"<DOCUMENT>(.*?)$", text, re.DOTALL | re.IGNORECASE)
    if partial and len(partial.group(1).strip()) > 100:
        return partial.group(1).strip()

    return None


def _response_signals_edit(text: str) -> bool:
    """Return True if the response text suggests the model made document changes."""
    return bool(_EDIT_SIGNALS.search(text)) and len(text) > 150


# ---------------------------------------------------------------------------
# Core stream_edit generator
# ---------------------------------------------------------------------------


async def stream_edit(
    provider: BaseProvider,
    prompt: str,
    document_html: str,
    history: list[dict],
) -> AsyncIterator[tuple[str, str]]:
    """
    Yield ``(event_type, data)`` tuples:

    ``("delta", text)``
        Streaming token chunk to display in the chat panel.

    ``("done", html)``
        Final event.  ``html`` is the extracted updated document HTML,
        or ``""`` if no document change was made.

    ``("notice", message)``
        Warning surfaced to the user (e.g. after all retries failed).
    """
    messages = build_messages(prompt, document_html, history)

    # ── Pass 1: streaming (real-time UX) ─────────────────────────────────
    full_text = ""
    display_buffer = ""
    in_document = False

    async for token in provider.stream_chat(messages):
        full_text += token

        if not in_document:
            display_buffer += token

            if DOCUMENT_OPEN.lower() in display_buffer.lower():
                # Locate the tag (case-insensitive)
                idx = display_buffer.lower().index(DOCUMENT_OPEN.lower())
                pre = display_buffer[:idx]
                if pre:
                    yield ("delta", pre)
                in_document = True
                display_buffer = ""
            else:
                # Emit safe portion; hold back LOOKAHEAD chars for split-tag detection
                if len(display_buffer) > LOOKAHEAD:
                    yield ("delta", display_buffer[:-LOOKAHEAD])
                    display_buffer = display_buffer[-LOOKAHEAD:]

    # Flush any remaining display buffer
    if display_buffer and not in_document:
        yield ("delta", display_buffer)

    # ── Extraction attempt ───────────────────────────────────────────────
    doc_html = extract_document_block(full_text)

    if doc_html is not None:
        yield ("done", doc_html)
        return

    # ── Pass 2+: retry if model clearly made edits but forgot the tags ───
    if _response_signals_edit(full_text):
        logger.info("Document block missing despite edit signals — retrying (max %d)", MAX_RETRIES)

        retry_messages = messages + [
            {"role": "assistant", "content": full_text},
            {"role": "user", "content": RETRY_PROMPT},
        ]

        for attempt in range(1, MAX_RETRIES + 1):
            logger.info("Retry attempt %d/%d", attempt, MAX_RETRIES)
            try:
                correction = await provider.chat(retry_messages)
                doc_html = extract_document_block(correction)
                if doc_html is not None:
                    logger.info("Extraction succeeded on retry %d", attempt)
                    yield ("done", doc_html)
                    return

                # Prepare next retry with the failed correction in context
                retry_messages = retry_messages + [
                    {"role": "assistant", "content": correction},
                    {"role": "user", "content": RETRY_PROMPT},
                ]
            except Exception as exc:  # noqa: BLE001
                logger.warning("Retry %d failed with error: %s", attempt, exc)
                break

        # All retries exhausted
        yield (
            "notice",
            "The model made changes but could not format them correctly. "
            "Try rephrasing your request or switching to a more capable model.",
        )

    yield ("done", "")
