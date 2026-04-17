# DocPilot Core Engine

`core-engine` is the FastAPI layer that the UI talks to directly. It brokers LLM providers, chat/session workflows, document import/export orchestration, and encrypted API key handling.

## Responsibilities

- Expose REST endpoints for chat, agent turns, document import/export, health, and key storage.
- Route model requests to providers such as Ollama, OpenAI, Anthropic, Groq, NVIDIA, Azure OpenAI, and other OpenAI-compatible endpoints.
- Call `doc-mcp` for canonical document operations.
- Persist chat state locally.

## Run locally

Requirements:

- Python 3.11+

```powershell
cd packages/core-engine
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env
uvicorn main:app --reload --port 8000
```

Interactive docs are available at `http://localhost:8000/docs`.

## Important configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `DOC_MCP_URL` | `http://localhost:8080` | Java document engine base URL |
| `CHAT_DATABASE_PATH` | `./data/docpilot.db` | Local chat SQLite file |
| `REQUEST_TIMEOUT_SECONDS` | `120` | Upstream request timeout |

Provider-specific keys and defaults live in `.env.example`.

## Notes about Qdrant

Qdrant is not integrated directly into `core-engine`. Semantic indexing and retrieval stay inside `doc-mcp`, because that is where the canonical document tree and revision lifecycle live.# DocPilot Core Engine

Python FastAPI service that acts as the **AI gateway** for the DocPilot desktop app.

- Exposes the MCP-backed `/api/agent/*` contract for ask / review / apply flows
- Proxies document-session operations to the Java doc-mcp service (internal, port 8080)
- Supports Ollama (default), OpenAI, Anthropic, and Azure OpenAI providers

## Requirements

- Python 3.11+
- [Ollama](https://ollama.com) running locally (for the default local LLM)
- Java doc-mcp service running on port 8080

## Setup

```bash
cd packages/core-engine

# Create virtual environment
python -m venv .venv
.venv\Scripts\activate        # Windows
# source .venv/bin/activate   # macOS / Linux

# Install dependencies
pip install -r requirements.txt

# Configure
cp .env.example .env
# Edit .env — at minimum set OLLAMA_DEFAULT_MODEL to a model you have pulled

# Run
python main.py
# or: uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Server runs on **http://localhost:8000**.

## Ollama quick-start

```bash
ollama pull llama3.2          # default model
ollama pull mistral           # alternative
```

## API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Health check |
| POST | `/api/agent/turn` | Phase 1 turn contract (JSON response) |
| POST | `/api/agent/turn/stream` | Phase 1 turn contract (SSE events) |
| GET | `/api/agent/sessions/{id}` | Session summary + revision list |
| GET | `/api/agent/sessions/{id}/projection` | Current projected HTML for the session |
| GET | `/api/agent/sessions/{id}/revisions` | Revision list for a session |
| GET | `/api/agent/revisions/{id}` | Revision details, including pending-review payload when relevant |
| POST | `/api/agent/revisions/{id}/apply` | Apply a staged revision and return refreshed HTML |
| POST | `/api/agent/revisions/{id}/reject` | Reject a staged revision |
| POST | `/api/agent/revisions/{id}/rollback` | Roll back the current applied revision |
| POST | `/api/agent/revisions/compare` | Compare two applied revisions |
| POST | `/api/documents/import` | Import DOCX/PDF through doc-mcp |
| POST | `/api/documents/export` | Export DOCX from a document session |

See **http://localhost:8000/docs** for interactive Swagger UI.

## SSE event format (`/api/agent/turn/stream`)

```
event: tool_started
data: {"tool": "inspect_document", ...}

event: assistant_delta
data: "assistant chunk"

event: done
data: {"message": "...", "revisionId": "rev_...", "proposal": {...}, "review": {...}}
```

Error:
```
event: notice
data: {"message": "Error: ..."}
```

Notes:

- `tool_started` / `tool_finished` can represent both document-engine steps and model phases. The backend now emits `llm_inference` with phases such as `compose_answer` and `plan_revision` so the UI can render Copilot-style inference progress.
- Ask turns stream provider output token by token through `assistant_delta` instead of waiting for a full non-streaming response.
- If `answer_about_document` returns no useful snippets, the backend falls back to compact analysis HTML derived from the imported DOCX and emits `get_analysis_html` activity before composing the answer.
- Agent turns now use an adaptive tool loop with a normalized multi-tool batch contract. The model can emit `call_tools` with `execution: "parallel" | "sequential"`, and the backend validates batch size, per-tool usage limits, heavy-tool limits, and approximate token budgets before executing it.
- Tools are classified by cost tier. Light tools may run in parallel when independent, while medium and heavy tools are forced through sequential planning. Heavy tools such as `get_source_html` and `get_analysis_html` are intentionally capped per turn.
- Approximate input-token budgeting is enforced per request. When a turn is close to the configured session window or exceeds the hard input cap, the backend automatically compacts older chat history and older tool results before the next LLM request.
- Source HTML is no longer prefetched for every agent turn. The backend only fetches it when the model explicitly asks for higher-fidelity document structure during the loop.

## Environment variables

See `.env.example` for all options. Key ones:

| Variable | Default | Description |
|----------|---------|-------------|
| `DOC_MCP_URL` | `http://localhost:8080` | Java doc-mcp service URL |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama URL |
| `OLLAMA_DEFAULT_MODEL` | `llama3.2` | Model to use |
| `AGENT_MAX_INPUT_TOKENS` | `6000` | Approximate hard cap for assembled input tokens per LLM request |
| `AGENT_SESSION_CONTEXT_BUDGET_TOKENS` | `4200` | Soft budget for chat history and loop transcript before compaction |
| `AGENT_TOOL_RESULT_BUDGET_TOKENS` | `2200` | Approximate per-batch allowance for tool results injected back into the prompt |
| `AGENT_MAX_TOOL_BATCH_SIZE` | `4` | Maximum number of tools the model may request in one batch |
| `AGENT_MAX_PARALLEL_TOOLS` | `3` | Maximum number of parallel light tools allowed in a batch |
| `AGENT_MAX_HEAVY_TOOLS_PER_TURN` | `1` | Maximum heavy-tool calls allowed across a single agent turn |
| `AGENT_AUTO_COMPACT_SESSION` | `true` | Enables proactive session-history compaction near the input budget |
| `OPENAI_API_KEY` | *(empty)* | Required for OpenAI provider |
| `NVIDIA_API_KEY` | *(empty)* | Required for NVIDIA provider |
| `ANTHROPIC_API_KEY` | *(empty)* | Required for Anthropic provider |

The desktop client may also override these values per request through `agentConfig` in `/api/agent/turn` and `/api/agent/turn/stream`.

## Debug traces

`core-engine` now writes detailed JSONL traces to `packages/core-engine/debug/core-engine-flow.jsonl`.

- Each request carries `X-DocPilot-Trace-Id` so a single turn can be correlated across desktop, core-engine, and doc-mcp logs.
- Frontend-originated debug events are persisted to `packages/desktop/debug/desktop-flow.jsonl` through `POST /api/debug/frontend`.
- LLM requests are logged with the full prompt/messages assembled by the orchestrator, plus full model outputs and doc-mcp request/response payloads.

## Frontend integration notes

- Imported DOCX files now return `documentSessionId` and should be treated as canonical session-backed documents.
- Ask / agent chat should use `/api/agent/turn` or `/api/agent/turn/stream`; `/api/chat` has been removed.
- Review UI should use the `/api/agent/revisions/*` endpoints instead of staging raw HTML diffs in the client.
- Export requires `documentSessionId`; raw HTML export is no longer supported.
