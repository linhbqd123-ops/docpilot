# DocPilot — AI Document Assistant for Microsoft Word

<div align="center">

**Intelligent document editing powered by AI agents and structured document operations.**

*Rewrite • Improve • Tailor CV • Generate*

</div>

---

## Overview

DocPilot is a production-ready AI assistant that integrates directly into Microsoft Word as a task pane add-in. It uses an agent-based architecture with a dedicated document operations service (DOC-MCP) and supports multiple LLM providers through a unified OpenAI-compatible interface.

### Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        Microsoft Word                            │
│  ┌────────────────────────────────────────┐                      │
│  │        DocPilot Task Pane (Office.js)  │                      │
│  │  ┌──────────┐ ┌────────┐ ┌─────────┐  │                      │
│  │  │ Chat UI  │ │Actions │ │Settings │  │                      │
│  │  └─────┬────┘ └───┬────┘ └────┬────┘  │                      │
│  └────────┼──────────┼───────────┼───────┘                      │
└───────────┼──────────┼───────────┼───────────────────────────────┘
            │   HTTP   │           │
            ▼          ▼           ▼
┌──────────────────────────────────────────────┐
│          Agent Backend (FastAPI :8000)        │
│  ┌──────────────────────────────────────┐    │
│  │         DocPilot Agent Loop          │    │
│  │  Intent → Tool Selection → Execute   │    │
│  │  → Observe → Continue/Complete       │    │
│  └──────────┬───────────────────────────┘    │
│             │                                 │
│  ┌──────────▼──────────┐  ┌───────────────┐  │
│  │    Agent Tools      │  │   LLM Layer   │  │
│  │  • extract_structure│  │  • Router     │  │
│  │  • rewrite_blocks   │  │  • Client     │  │
│  │  • generate_doc     │  │  • JSON Parse │  │
│  │  • apply_changes    │  │  • Fallback   │  │
│  └──────────┬──────────┘  └───────┬───────┘  │
└─────────────┼─────────────────────┼──────────┘
              │ HTTP                │ HTTP
              ▼                    ▼
┌─────────────────────────┐  ┌─────────────────┐
│ DOC-MCP Service (:8001) │  │  LLM Providers  │
│  • /extract-structure   │  │  • Ollama       │
│  • /apply-changes       │  │  • Groq         │
│  • /clear-document      │  │  • OpenRouter   │
│  • /insert-content      │  │  • OpenAI       │
│  (python-docx engine)   │  │  • LM Studio    │
└─────────────────────────┘  └─────────────────┘
```

---

## Features

| Feature | Description |
|---------|-------------|
| **Preserve Mode** | Rewrite text block-by-block while keeping all formatting, styles, and document structure intact |
| **Rebuild Mode** | Generate a completely new document from scratch with proper heading hierarchy |
| **Improve** | Enhance grammar, clarity, and professionalism of existing content |
| **Tailor CV** | Adapt a resume to match a specific job description |
| **Multi-Provider** | Supports Ollama, Groq, OpenRouter, OpenAI, LM Studio, and any OpenAI-compatible API |
| **Auto Fallback** | Automatically falls back to alternative providers on failure |
| **JSON Enforcement** | Validates and retries LLM responses to ensure valid structured output |

---

## Project Structure

```
docpilot/
├── agent-backend/          # AI Agent Backend (FastAPI)
│   ├── app/
│   │   ├── agent/          # Core agent loop
│   │   │   └── agent.py    # DocPilotAgent class
│   │   ├── api/            # REST API endpoints
│   │   │   └── routes.py   # /agent/run, /agent/status
│   │   ├── core/           # Configuration
│   │   ├── prompts/        # Prompt templates
│   │   │   └── templates.py
│   │   ├── tools/          # Agent tools (DOC-MCP client)
│   │   │   └── document_tools.py
│   │   └── main.py         # FastAPI app entry point
│   ├── Dockerfile
│   └── requirements.txt
│
├── doc-mcp-service/        # Document MCP Server (FastAPI)
│   ├── app/
│   │   ├── api/            # REST API endpoints
│   │   │   └── routes.py   # /extract-structure, /apply-changes, etc.
│   │   ├── core/           # Configuration
│   │   ├── models/         # Pydantic models (DocumentBlock, etc.)
│   │   │   └── document.py
│   │   ├── services/       # Document processing engine
│   │   │   └── document_service.py
│   │   └── main.py         # FastAPI app entry point
│   ├── Dockerfile
│   └── requirements.txt
│
├── llm-layer/              # LLM Integration Layer
│   ├── llm_core/
│   │   ├── core/           # Router & config loader
│   │   │   ├── config.py
│   │   │   └── router.py   # LLMRouter with fallback & JSON enforcement
│   │   ├── models/         # LLM data models
│   │   │   └── llm_models.py
│   │   └── providers/      # OpenAI-compatible client
│   │       └── openai_client.py
│   └── requirements.txt
│
├── frontend/               # Word Add-in (Office.js)
│   ├── src/
│   │   ├── taskpane.html   # Task pane UI
│   │   ├── taskpane.css    # Styles (Copilot-like design)
│   │   └── taskpane.js     # Office.js + API communication
│   ├── assets/             # Icons for the add-in
│   ├── manifest.xml        # Office Add-in manifest
│   └── package.json
│
├── config/                 # Configuration files
│   ├── llm_config.json     # LLM provider configuration
│   └── llm_config.example.json
│
├── docker-compose.yml      # Docker orchestration
├── start-dev.bat           # Windows local dev startup
├── start-dev.sh            # Linux/Mac local dev startup
├── .env.example            # Environment variables template
├── .gitignore
└── README.md               # This file
```

---

## Quick Start

### Prerequisites

- **Python 3.11+**
- **Node.js 18+** (for frontend dev server)
- **Microsoft Word** (desktop version for add-in sideloading)
- **An LLM provider** — one of:
  - [Ollama](https://ollama.com/) (local, free) — **recommended for getting started**
  - Groq API key (cloud, fast)
  - OpenRouter API key (cloud, many models)
  - OpenAI API key (cloud)

### Option A: Docker (Recommended)

```bash
# 1. Clone and configure
cp .env.example .env
# Edit .env with your API keys (optional for local-only setup)

# 2. Start services
docker compose up --build

# 3. Verify
# DOC-MCP: http://localhost:8001/docs
# Agent:   http://localhost:8000/docs
```

### Option B: Local Development

**Windows:**
```batch
# 1. Configure
copy .env.example .env

# 2. Start all services
start-dev.bat
```

**Linux/macOS:**
```bash
# 1. Configure
cp .env.example .env

# 2. Make script executable and run
chmod +x start-dev.sh
./start-dev.sh
```

**Manual start (any OS):**
```bash
# Terminal 1: DOC-MCP Service
cd doc-mcp-service
pip install -r requirements.txt
uvicorn app.main:app --port 8001 --reload

# Terminal 2: Agent Backend
cd agent-backend
pip install -r requirements.txt
pip install -r ../llm-layer/requirements.txt
# Windows:
set PYTHONPATH=..\llm-layer;%PYTHONPATH%
# Linux/Mac:
export PYTHONPATH=../llm-layer:$PYTHONPATH
uvicorn app.main:app --port 8000 --reload

# Terminal 3: Frontend
cd frontend
npx http-server . -p 3000 --cors
```

### Setting Up the Word Add-in

1. Open Microsoft Word
2. Go to **Insert** → **Add-ins** → **My Add-ins** → **Shared Folder** (or use sideloading)
3. For development, use the [Office Add-in Sideloading guide](https://learn.microsoft.com/en-us/office/dev/add-ins/testing/sideload-office-add-ins-for-testing):
   - Place `frontend/manifest.xml` in a shared folder
   - Or use: `cd frontend && npx office-addin-dev-certs install && npm run start`
4. The DocPilot panel appears in the **Home** tab

### Setting Up Ollama (Local LLM)

```bash
# Install Ollama from https://ollama.com/

# Pull a model
ollama pull qwen2.5

# Ollama runs on port 11434 by default — DocPilot is pre-configured for this
```

---

## Configuration

### LLM Providers

Edit `config/llm_config.json` to configure your LLM providers:

```json
{
    "providers": [
        {
            "name": "local",
            "base_url": "http://localhost:11434/v1",
            "model": "qwen2.5",
            "tier": "local",
            "max_tokens": 4096,
            "temperature": 0.3
        },
        {
            "name": "groq",
            "base_url": "https://api.groq.com/openai/v1",
            "api_key": "gsk_...",
            "model": "llama-3.3-70b-versatile",
            "tier": "fast",
            "is_fallback": true
        }
    ],
    "default_provider": "local",
    "auto_tier": "fast",
    "max_retries": 3,
    "json_retry_attempts": 2
}
```

**Provider Tiers:**
| Tier | Description | Example |
|------|-------------|---------|
| `local` | Local models (free, private) | Ollama, LM Studio |
| `fast` | Cloud models optimized for speed | Groq |
| `quality` | Cloud models optimized for quality | Claude, GPT-4 |
| `custom` | Custom/other providers | Any OpenAI-compatible API |

**API keys via environment variables:**
```bash
LLM_PROVIDER_GROQ_API_KEY=gsk_...
LLM_PROVIDER_OPENROUTER_API_KEY=sk-or-...
LLM_PROVIDER_OPENAI_API_KEY=sk-...
```

---

## API Reference

### Agent Backend (`:8000`)

#### `POST /api/v1/agent/run`

Execute the AI agent on a document.

```json
{
    "message": "Rewrite this document in a more professional tone",
    "document_base64": "UEsDBBQ...",
    "mode": "preserve",
    "action": "rewrite",
    "provider_name": "local"
}
```

**Parameters:**
| Field | Type | Description |
|-------|------|-------------|
| `message` | string | User instruction |
| `document_base64` | string? | Base64-encoded .docx file |
| `mode` | string? | `preserve` or `rebuild` |
| `action` | string? | `rewrite`, `improve`, `tailor_cv`, `generate` |
| `provider_name` | string? | Specific LLM provider to use |

**Response:**
```json
{
    "success": true,
    "message": "Rewrote 5 paragraphs with a more professional tone",
    "document_base64": "UEsDBBQ...",
    "changes_summary": "Improved formality in introduction and experience sections",
    "structure": { "blocks": [...] }
}
```

#### `GET /api/v1/agent/status`

Get agent status and available providers.

### DOC-MCP Service (`:8001`)

#### `POST /api/v1/extract-structure`
Extract structured blocks from a Word document.

#### `POST /api/v1/apply-changes`
Apply block-level changes to a document.

#### `POST /api/v1/clear-document`
Clear all document content.

#### `POST /api/v1/insert-structured-content`
Insert new structured blocks.

Full API documentation available at `/docs` (Swagger UI) on each service.

---

## Agent Modes

### Preserve Mode
Rewrites text while keeping the original document structure and formatting intact:
- Heading styles are preserved
- List formatting is maintained  
- Table structure remains unchanged
- Only text content is modified

### Rebuild Mode
Generates a completely new document:
- Creates proper heading hierarchy
- Structures content with appropriate styles
- Supports tables and lists
- Replaces entire document content

---

## Safety & Security

- **No shell execution** — The agent only uses defined tools (extract, rewrite, apply)
- **No arbitrary code execution** — All operations go through the DOC-MCP REST API
- **Input validation** — All inputs validated with Pydantic models
- **JSON enforcement** — LLM responses are validated and retried if not valid JSON
- **CORS configured** — Frontend-backend communication properly configured

---

## Development

### Adding a New LLM Provider

1. Add provider config to `config/llm_config.json`
2. Set the API key in `.env` as `LLM_PROVIDER_{NAME}_API_KEY`
3. The provider is automatically available in the UI

### Adding a New Agent Tool

1. Define the tool in `agent-backend/app/tools/document_tools.py`
2. Add it to `TOOL_REGISTRY`
3. Handle it in the agent loop (`agent-backend/app/agent/agent.py`)

### Modifying Prompts

All prompts are in `agent-backend/app/prompts/templates.py`. Each prompt:
- Enforces JSON output format
- Includes clear instructions and constraints
- Is deterministic with low temperature

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Backend not connecting | Check that both services are running (`/health` endpoints) |
| LLM timeout | Increase `timeout` in provider config. For Ollama, ensure model is loaded |
| Invalid JSON from LLM | System auto-retries. If persistent, try a larger model |
| Add-in not loading | Check manifest.xml URLs match your dev server. Ensure HTTPS for production |
| Document not updating | Verify the add-in has ReadWriteDocument permissions |

---

## License

MIT
