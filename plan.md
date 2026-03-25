# DocPilot — Implementation Plan (Local-first, shareable)

Mục tiêu: cung cấp một bản hướng dẫn và checklist chi tiết để một coding agent khác có thể hoàn thiện, chạy và chia sẻ DocPilot hoàn toàn local (mỗi dev chạy trên máy của họ) — KHÔNG cần host cloud hoặc Postgres ban đầu.
Tóm tắt chiến lược:
- Chạy mọi service (DOC‑MCP, Agent backend, Frontend) trên máy dev cá nhân.
- Tránh truyền file .docx dưới dạng base64 trực tiếp vào LLM; thay flow cho local testing: upload (local file or in-memory) → extract structured blocks → agent nhận cấu trúc JSON → LLM trả JSON/tool_call → agent apply changes qua DOC‑MCP.

Yêu cầu môi trường (local developer):
- Windows: Python 3.11+, Node.js 18+, Microsoft Word desktop
- Ollama (optional, recommended for local LLM) hoặc OpenAI-compatible API key nếu dùng cloud
- Git, Docker (optional — chỉ cần khi dev muốn dockerized run)

Chi tiết cho coding agent (work items):
1) Bảo đảm services chạy local và binding
  - DOC‑MCP: FastAPI app nghe trên 0.0.0.0:8001
  - Agent backend: FastAPI app nghe trên 0.0.0.0:8000
  - Frontend dev server: http(s) server trên port 3000 (http-server hoặc simple static server).
  - Đảm bảo CORS cho frontend → backend (localhost & 127.0.0.1).

2) Endpoints và contract cần giữ chính xác (cho front/backend và tool integration):
  - DOC‑MCP
    - POST /api/v1/extract-structure { document_base64 } -> DocumentStructure (blocks[])
    - POST /api/v1/apply-changes { document_base64, changes[] } -> { document_base64, changes_summary }
    - POST /api/v1/clear-document { document_base64 } -> { document_base64 }
    - POST /api/v1/insert-structured-content { document_base64, blocks[], position } -> { document_base64 }
  - Agent backend
    - POST /api/v1/agent/run { message, document_base64?, mode, action, provider_name? } -> agent result (success, message, document_base64, changes_summary, structure)
    - GET /api/v1/agent/status -> providers, health

3) LLM layer expectations
  - Keep `llm_core` router API: `complete(prompt, provider)` and `complete_json(prompt, schema)` with retry-on-invalid-JSON logic.
  - Default low temperature, deterministic prompts; ensure JSON schema in templates.

4) Agent behavior for local-only flow
  - When `document_base64` provided: call DOC‑MCP `/extract-structure` to get blocks and pass only the structured JSON (not full base64) to LLM prompts.
  - LLM returns a JSON describing tool calls (which blocks to change / new text per block). Agent calls DOC‑MCP `/apply-changes` with the original base64 + changes array.
  - For `rebuild` mode: agent may call `/clear-document` then `/insert-structured-content` with newly generated blocks.

5) Storage & history (local-first)
  - Default: do NOT add Postgres or Redis.
  - Optional local persistence:
    - Use SQLite via `sqlmodel` for lightweight history (file: `.data/docpilot_history.db`).
    - Schema minimal: Document (id, filename, uploaded_at), Conversation (id, doc_id, user_message, agent_response, timestamp), Events/Edits (id, conv_id, changes JSON).
    - Alternative simpler: store per-session JSON files under `.data/sessions/<timestamp>.json`.
  - Embeddings/Vector search: postpone. If needed locally, use small file-based index (faiss/docker) or local pgvector later.

6) Frontend / Word Add-in (local run & sharing among colleagues)
  - For each dev to run independently: sideload `frontend/manifest.xml` pointing to local frontend URL (http://localhost:3000 or a local file path for static manifest) and ensure Word has ReadWriteDocument permissions.
  - Frontend must call Backend at `http://localhost:8000` and DOC‑MCP at `http://localhost:8001` (or agent backend acts as single API proxy).
  - For HTTPS requirement in Office Add-ins: either use `npx office-addin-dev-certs install` + `npm run start` (provides HTTPS) OR use the Add-in sideload method that allows shared folder manifest (no HTTPS for manifest file but web assets still may require HTTPS on some platforms). Document exact sideload steps in README (already included).

7) Dev & test checklist (what to run locally)
  - Copy `.env.example` → `.env` and set any API keys needed for LLMs (or leave blank to use local Ollama default).
  - Windows quick-start (recommended path):
  ```powershell
  cd doc-mcp-service
  .\.venv\Scripts\Activate.ps1  # or use start-dev.bat which creates .venv
  pip install -r requirements.txt
  uvicorn app.main:app --port 8001 --reload

  cd ..\agent-backend
  .\.venv\Scripts\Activate.ps1
  pip install -r requirements.txt
  set PYTHONPATH=..\llm-layer;%PYTHONPATH%
  uvicorn app.main:app --port 8000 --reload

  cd ..\frontend
  npm install
  npx http-server . -p 3000 --cors
  ```

  - Sideloading add-in in Word: follow README steps; use `manifest.xml` from `frontend` and ensure `taskpane.html` URL matches dev server or local file.

8) Lightweight sharing options (no cloud host)
  - Option A (each dev runs local): simplest — every dev clones and runs services. Good for small teams.
  - Option B (one machine as LAN host): single host runs services; colleagues sideload manifest pointing to host IP; if HTTPS required, use `ngrok http 3000` and update frontend URL in manifest.
  - Document security note: if exposing services on LAN or via ngrok, do not expose LLM API keys publicly.

9) Testing and verification notes for the coding agent
  - End-to-end manual test: open Word → sideload add-in → load a small .docx → press rewrite/improve/tailor → inspect results in Word and returned `changes_summary`.
  - Unit tests: add tests for `document_service.extract_structure` and `apply_changes` to ensure formatting preserved (use small .docx fixtures in `tests/fixtures`).
  - Integration tests: simulate agent run by calling `/api/v1/agent/run` with `mode=preserve` and a base64 of a small docx; assert that returned `document_base64` opens in Word or decodes to a docx with changed text.

10) Deliverables for handoff to another coding agent
  - Update `README.md` with explicit per-developer local run steps (Windows & macOS/Linux) — keep the Docker option but emphasize local-first.
  - Ensure `start-dev.bat` and `start-dev.sh` create and reuse `.venv` (already implemented) and include commands to start both services quickly.
  - Add a new section in `plan.md` (this file) named **Local-run Handoff** containing exact URLs, ports, example requests for `curl` or `httpie` for the main endpoints.
  - Provide small `.docx` fixtures for testing under `tests/fixtures/sample.docx` (3 paragraphs, headings, a list, a table).

11) Notes & constraints for implementer
  - Avoid sending full base64 to LLM in prompts. Always pass structured `blocks[]` JSON capturing block id, type, text, style metadata.
  - Keep LLM prompts deterministic and use `complete_json` with schema validation and 2 retries on parse failures.
  - Do not introduce cloud DB or background workers in this local-first handoff — those are separate follow-ups.

Local-run Handoff — Quick examples (to include in README):
- Extract structure (curl):
  ```bash
  curl -X POST http://localhost:8001/api/v1/extract-structure \
    -H 'Content-Type: application/json' \
    -d '{"document_base64":"<BASE64>"}'
  ```
- Run agent (curl):
  ```bash
  curl -X POST http://localhost:8000/api/v1/agent/run \
    -H 'Content-Type: application/json' \
    -d '{"message":"Rewrite in a professional tone","document_base64":"<BASE64>","mode":"preserve","action":"rewrite"}'
  ```

---

# 🔄 PERSISTENCE LAYER & EVENT-DRIVEN ARCHITECTURE

## Overview
Lightweight SQLite-based persistence + async event-driven pipeline for:
- Conversation history storage
- Event log for audit trail and embedding pipeline
- Document versioning and edit tracking
- Future scalability to Redis/RabbitMQ without code changes

## Architecture

### Database Layer (SQLite)
- **Location**: `.data/docpilot.db` (mounted in Docker at `/app/.data`)
- **ORM**: SQLModel (async SQLAlchemy + Pydantic)
- **Driver**: aiosqlite (non-blocking async SQLite)

**Database Schema**:
```
Document (id, filename, content_base64, metadata JSON, embedding JSON, uploaded_at, updated_at)
│
├─ Conversation (id, doc_id FK, user_message, agent_response, action, mode, provider_used, changes_summary, success, tokens_used, created_at)
│
├─ Event (id, event_type, doc_id? FK, conversation_id? FK, payload JSON, processed, retry_count, error_message, created_at, processed_at)
│
└─ Edit (id, conversation_id FK, document_version, changes JSON, created_at)
```

**Event Types** (defined in `EventType` enum):
- `DOCUMENT_UPLOADED`: When a new document is stored
- `CONVERSATION_STARTED`: When agent begins processing
- `CONVERSATION_COMPLETED`: When agent finishes (includes success, tokens_used)
- `EMBEDDING_GENERATED`: When embeddings are created (future)
- `EMBEDDING_FAILED`: Embedding pipeline error (future)
- `VECTOR_INDEXED`: When embedding is indexed in vector DB (future)

### Event Bus (async pub/sub)
- **Implementation**: In-memory asyncio.Queue with handler dispatch
- **Location**: `app/persistence/event_bus.py`
- **Scalability Path**: asyncio.Queue (local) → Redis Streams → AWS SQS/RabbitMQ

**Event Publishing** (from agent):
```python
# When conversation ends:
await event_bus.publish(DomainEvent(
    event_type=EventType.CONVERSATION_COMPLETED,
    doc_id=doc_id,
    conversation_id=conv_id,
    payload={"action": "rewrite", "success": True, "tokens_used": 1200}
))
```

**Event Subscription** (for embedding pipeline):
```python
async def on_embedding_needed(event: DomainEvent):
    """Called when EMBEDDING_GENERATED event fires."""
    embeddings = await llm_router.complete_embeddings(event.payload["text"])
    await db_service.update_document_embedding(event.doc_id, embeddings)

event_bus.subscribe(EventType.EMBEDDING_GENERATED, on_embedding_needed)
```

### API Endpoints (Persistence)

**Upload & Store Document**:
```bash
POST /api/v1/documents/upload
Content-Type: application/json

{
  "filename": "CV.docx",
  "content_base64": "UEsDBBQ...",
  "metadata": {"user_id": "alice", "category": "resume"}
}

→ Response:
{
  "id": 42,
  "filename": "CV.docx",
  "metadata": {...},
  "uploaded_at": "2024-01-15T10:30:00Z"
}

→ Side effect: Event DOCUMENT_UPLOADED published to event bus
```

**Get Conversation History** (per document):
```bash
GET /api/v1/documents/{doc_id}/history

→ Response:
[
  {
    "id": 1,
    "doc_id": 42,
    "user_message": "Rewrite in professional tone",
    "agent_response": "...",
    "action": "rewrite",
    "provider_used": "gpt-4-turbo",
    "success": true,
    "created_at": "2024-01-15T10:31:00Z"
  },
  ...
]
```

**List Events** (for embedding workers, etc.):
```bash
GET /api/v1/events?processed=false

→ Response:
[
  {
    "id": 101,
    "event_type": "CONVERSATION_COMPLETED",
    "doc_id": 42,
    "conversation_id": 1,
    "payload": {"action": "rewrite", "success": true, "tokens_used": 1200},
    "processed": false,
    "retry_count": 0,
    "created_at": "2024-01-15T10:31:00Z"
  },
  ...
]
```

**Publish Custom Event** (for testing or external integrations):
```bash
POST /api/v1/events
Content-Type: application/json

{
  "event_type": "EMBEDDING_GENERATED",
  "doc_id": 42,
  "payload": {"text": "Resume content here", "model": "text-embedding-3-small"}
}

→ Response: Event object (as above)
```

## Integration Points

### In Agent Backend (`agent-backend/app/main.py`):
```python
# Lifespan: initialize persistence on startup
await db_service.init()              # Create tables, open connection pool
app.state.db = db_service
app.state.event_bus = event_bus

# Start event bus in background task
asyncio.create_task(event_bus.start())
```

### In Agent Workflow:
```python
# After agent processes a document
conv = await ConversationRepository.log_conversation(
    doc_id=42,
    user_message="Rewrite professionally",
    agent_response="I've rewritten your document...",
    action="rewrite",
    success=True,
    tokens_used=1200,
)
# ^ Automatically publishes CONVERSATION_COMPLETED event
```

### Repository Layer (`app/persistence/repository.py`):
Provides high-level methods for common operations:
- `log_conversation()` - save conv turn + publish event
- `publish_event()` - emit event to bus + store in DB
- `get_conversation_history()` - fetch history for a doc
- `log_edit()` - audit edit operations

## Scalability Path

### Phase 1 (Current — Local Dev):
- SQLite + in-memory event queue
- Single server, no worker processes
- ✅ Lightweight, zero external deps, fast for dev

### Phase 2 (Team Scaling):
- Replace SQLite with PostgreSQL (schema unchanged)
- Replace aiosqlite with asyncpg
- Event bus still in-memory (if <50 events/sec)

### Phase 3 (Production - Event Pipeline):
- Replace event bus with Redis Streams
  ```python
  class RedisBus:
      async def publish(self, event):
          await redis.xadd("events", {"data": event.json()})
      
      async def start(self):
          # Subscribe to stream, dispatch handlers as before
  ```
- Spawn separate embedding service listening to `EMBEDDING_GENERATED` events
- Spawn separate logging service archiving old events to cold storage

### Code Breakpoints (ready for migration):
- **DB Layer**: Change only `db_service` implementation (SQLModel already async-native)
- **Event Bus**: Implement new `EventBus` subclass, swap one line in `main.py`
- **API**: No changes — endpoints are agnostic to backend

## Configuration

### Environment Variables (`.env`)
```bash
# Database (optional)
DATABASE_URL=sqlite:///.data/docpilot.db   # Default: SQLite local
# DATABASE_URL=postgresql+asyncpg://user:pass@localhost/docpilot  # For Postgres later

# Event Bus (optional)
EVENT_BUS_TYPE=memory                       # Options: memory, redis
# REDIS_URL=redis://localhost:6379/0       # If using Redis

# Persistence
PERSISTENCE_ENABLED=true                    # Enable/disable storage
ENABLE_EMBEDDING_PIPELINE=false             # Enable embedding on event
```

### Docker Compose (Updated)
```yaml
version: '3.9'

services:
  doc-mcp:
    build: ./doc-mcp-service
    ports:
      - "8001:8001"
    volumes:
      - ./.data:/app/.data  # Mount SQLite DB
    environment:
      - LOG_LEVEL=INFO

  agent-backend:
    build: ./agent-backend
    ports:
      - "8000:8000"
    volumes:
      - ./.data:/app/.data  # Shared SQLite DB volume
    environment:
      - DATABASE_URL=sqlite:///.data/docpilot.db
      - DOC_MCP_URL=http://doc-mcp:8001

  frontend:
    image: httpserver:python
    ports:
      - "3000:3000"
    volumes:
      - ./frontend:/app
```

## Testing Persistence

**Unit test** for repository:
```python
async def test_log_conversation_publishes_event():
    conv = await ConversationRepository.log_conversation(
        doc_id=1,
        user_message="Rewrite",
        agent_response="Done",
        success=True,
    )
    assert conv.id > 0
    
    # Verify event was published
    await asyncio.sleep(0.1)  # Let event bus process
    events = await db_service.get_pending_events()
    assert len(events) > 0
    assert events[0].event_type == "CONVERSATION_COMPLETED"
```

**Integration test** (E2E with embedding):
```python
async def test_embedding_pipeline():
    # 1. Upload document
    doc = await db_service.create_document(Document(...))
    
    # 2. Handler subscribes to EMBEDDING_GENERATED
    embeddings_processed = []
    
    async def on_embedding(event):
        embeddings = await llm_router.complete_embeddings(...)
        embeddings_processed.append(embeddings)
    
    event_bus.subscribe(EventType.EMBEDDING_GENERATED, on_embedding)
    
    # 3. Trigger embedding
    await event_bus.publish(DomainEvent(
        event_type=EventType.EMBEDDING_GENERATED,
        doc_id=doc.id,
        payload={"text": "Resume content"}
    ))
    
    # 4. Wait for handler
    await asyncio.sleep(0.5)
    assert len(embeddings_processed) > 0
```

## Next Steps (Post-Implementation)

1. **Vector Search** (when needed):
   - Add `pgvector` extension to PostgreSQL
   - Store embeddings in `Document.embedding` field
   - Query by semantic similarity

2. **Embedding Service** (separate worker):
   - Deploy as standalone service listening to `EMBEDDING_GENERATED` events
   - Scales independently: `n` embedding workers processing queue in parallel

3. **Event Replay** (audit trail):
   - Archive old events to S3
   - Replay from checkpoint for recovery or analysis

4. **Webhooks** (external integrations):
   - Publish events to external URLs (e.g., Slack bot on conversation complete)

---

-- END --

Implement a proper AI agent system using Python.

Requirements:

### Agent Design

* Tool-based agent (NO arbitrary code execution)
* Must implement an agent loop:

Pseudo-loop:

1. Receive user intent + document context
2. Decide which tool to call
3. Call tool
4. Observe result
5. Continue until done

* Use frameworks like:

  * LangChain OR
  * CrewAI OR
  * custom lightweight agent loop

---

### Core Agent Capabilities

* Detect mode:

  * PRESERVE (structured rewrite)
  * REBUILD (generate from scratch)

* Handle document as structured data:

  * paragraph
  * list
  * table cell

* Maintain formatting awareness:

  * style name
  * block type

---

## 3. DOC-MCP Service (CRITICAL)

Create a separate Python service that acts as a "Document MCP Server".

This service must:

* Encapsulate ALL Word document operations
* Be designed as a standalone service (future MCP server)

### Requirements:

* Expose REST API:

  * /extract-structure
  * /apply-changes
  * /clear-document
  * /insert-structured-content

* Internally operate on structured JSON:

Example:
{
"blocks": [
{
"id": "1",
"type": "paragraph",
"style": "Heading1",
"text": "Experience"
}
]
}

* Must support:

  * paragraphs
  * lists
  * tables (rows, cells)

* Design clean abstraction so it can later become a true MCP server

Reference:
https://github.com/GongRzhe/Office-Word-MCP-Server

---

# 🧠 LLM INTEGRATION (CRITICAL)

Implement a unified LLM client using OpenAI-compatible API.

### Requirements:

* Support ANY provider with:

  * baseUrl
  * apiKey
  * model

* Example config:
  {
  "providers": [
  {
  "name": "local",
  "baseUrl": "http://localhost:11434/v1",
  "model": "qwen2.5"
  },
  {
  "name": "groq",
  "baseUrl": "...",
  "apiKey": "...",
  "model": "mixtral"
  }
  ]
  }

* should support local model also (ollama/lmStudio)

---

### LLM Router

Implement:

* provider selection (auto/manual)
* fallback strategy
* retry on failure

---

### JSON Enforcement

* ALL LLM responses must be valid JSON
* Implement:

  * validation
  * retry with stricter prompt if invalid

---

# 🧠 AGENT TOOLS

Define tools such as:

* extract_document_structure
* rewrite_blocks
* generate_document
* apply_changes

These tools must call the DOC-MCP service.

---

# 🧠 MODES

## PRESERVE MODE

* Rewrite text block-by-block
* Keep formatting intact

## REBUILD MODE

* Generate new structured document
* Apply template

---

# 🧠 PROMPT ENGINEERING

Create separate prompts for:

* preserve rewrite
* rebuild generation
* classification (optional)

All prompts must:

* enforce JSON output
* be deterministic

---

# 🎨 FRONTEND (Word Add-in)

* Build task pane UI similar to Copilot:

  * Chat box
  * Action buttons
  * Model selector
  * Mode selector

* Use Office.js to:

  * send document content to backend
  * apply updates returned from backend

---

# 🔐 SAFETY

* No shell execution
* No arbitrary code execution
* Only tool-based operations
* Validate all inputs/outputs

---

# 🚀 DEPLOYMENT

* Backend: FastAPI
* DOC-MCP: separate FastAPI service
* Frontend: static web (served locally or hosted)

Provide:

* docker-compose setup
* environment config
* easy local setup instructions

---

# 📦 OUTPUT REQUIREMENTS

Generate:

1. Full project structure:

   * /frontend (Word Add-in)
   * /agent-backend
   * /doc-mcp-service
   * /llm-layer

2. Complete working code

3. manifest.xml for Word Add-in

4. FastAPI endpoints

5. Agent implementation

6. LLM client + router

7. Example config files

8. Setup instructions

---

# 🎯 GOAL

The final system must:

* Run locally
* Connect to Word
* Rewrite documents with preserved formatting
* Support multiple LLM providers
* Be extensible into a full MCP ecosystem

Ensure the implementation is clean, modular, and production-ready.

# Note

* No need for any lint code check tool but have to implement the code best practice using standard desgin/coding pattern.
* Best practice config management.
* Real world ai agents app.
* Good UI/UX is important.
