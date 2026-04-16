# Phase 3 FE Contract

This document describes the backend contract the desktop frontend should target after the legacy HTML-in-prompt flow was removed.

## Document identity

Imported DOCX documents now have these canonical identifiers:

- `docId`: original uploaded document artifact id.
- `documentSessionId`: canonical doc-mcp session id.
- `baseRevisionId`: revision the UI is currently based on. `null` for a fresh import.

The frontend should keep `documentSessionId` as the primary document key for edit/review/export flows.

## Import and export

### `POST /api/documents/import`

DOCX response:

```json
{
  "docId": "...",
  "documentSessionId": "...",
  "baseRevisionId": null,
  "html": "<article ...>",
  "wordCount": 1234,
  "pageCount": 0,
  "filename": "contract.docx"
}
```

PDF response stays HTML-based but is not session-backed.

### `POST /api/documents/export`

Request:

```json
{
  "documentSessionId": "...",
  "filename": "contract.docx"
}
```

Raw HTML export is no longer supported.

## Ask and agent turns

### `POST /api/agent/turn`

Request:

```json
{
  "chatId": "chat_123",
  "provider": "ollama",
  "model": "llama3.2",
  "documentSessionId": "session_123",
  "mode": "ask",
  "baseRevisionId": "rev_123",
  "prompt": "Rewrite the introduction to sound more formal.",
  "selection": {
    "blockId": "block_123",
    "textRange": { "start": 0, "end": 42 }
  },
  "workspaceContext": {
    "documentIds": ["doc_123"],
    "activePane": "editor",
    "visibleBlockIds": ["block_123", "block_124"]
  },
  "history": [
    { "role": "user", "content": "..." },
    { "role": "assistant", "content": "..." }
  ]
}
```

Response when the turn only answers:

```json
{
  "chatId": "chat_123",
  "message": "...",
  "mode": "ask",
  "intent": "question",
  "resultType": "answer",
  "documentSessionId": "session_123",
  "baseRevisionId": "rev_123",
  "revisionId": null,
  "status": "completed"
}
```

Response when agent mode stages a revision:

```json
{
  "chatId": "chat_123",
  "message": "...",
  "mode": "agent",
  "intent": "edit",
  "resultType": "revision_staged",
  "documentSessionId": "session_123",
  "baseRevisionId": "rev_123",
  "revisionId": "rev_456",
  "status": "completed",
  "proposal": { "revision_id": "rev_456", "status": "PENDING", "validation": { ... } },
  "review": { ... },
  "toolActivity": [ ... ],
  "notices": [ ... ]
}
```

### `POST /api/agent/turn/stream`

SSE events:

- `tool_started`
- `tool_finished`
- `assistant_delta`
- `notice`
- `done`

Notes:

- `tool_started` / `tool_finished` may include regular doc-mcp tool names such as `answer_about_document`, `inspect_document`, `locate_relevant_context`, `propose_document_edit`, plus `llm_inference` phases like `compose_answer` and `plan_revision`.
- When retrieval does not yield enough snippets for an ask turn, the backend may emit `get_html_projection` activity and use the current projected document text as a fallback context source.
- Frontends should surface tool activity progressively during the stream so users can see inference steps while the answer is being generated.

## Session and revision APIs

### `GET /api/agent/sessions/{sessionId}`

Returns session summary plus revision list.

### `GET /api/agent/sessions/{sessionId}/projection`

Returns the current projected HTML for the canonical session.

### `GET /api/agent/sessions/{sessionId}/revisions`

Returns the revision list, optionally filtered by `status`.

### `GET /api/agent/revisions/{revisionId}`

Returns the stored revision. For `PENDING` revisions it also includes the `review` payload from doc-mcp.

### `POST /api/agent/revisions/{revisionId}/apply`

Applies the pending revision and returns refreshed session payload and current HTML projection.

### `POST /api/agent/revisions/{revisionId}/reject`

Rejects the pending revision and returns refreshed session payload.

### `POST /api/agent/revisions/{revisionId}/rollback`

Rolls back the current applied revision and returns refreshed session payload plus current HTML projection.

### `POST /api/agent/revisions/compare`

Request:

```json
{
  "documentSessionId": "session_123",
  "baseRevisionId": "rev_123",
  "targetRevisionId": "rev_456"
}
```

## Frontend state migration

Legacy client state to remove:

- `pendingHtml`
- `documentHtml` chat replies
- `/api/chat`
- optimistic manual HTML staging as the primary review model

New state to add:

- `documentSessionId`
- `baseRevisionId`
- `currentRevisionId`
- `pendingRevisionId`
- `revisionStatus`
- `reviewPayload`

Suggested FE flow:

1. Import DOCX and store `documentSessionId`.
2. Render current HTML from the import response or `/api/agent/sessions/{id}/projection`.
3. Send ask/agent turns through `/api/agent/turn`.
4. When `resultType === "revision_staged"`, show review UI from `review`.
5. Apply/reject through `/api/agent/revisions/{id}/apply` or `/reject`.
6. After apply or rollback, replace editor content from returned `html` and update revision ids from returned session payload.