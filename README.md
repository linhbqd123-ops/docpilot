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

DocPilot requires the following software to be installed on your machine:

#### Required Software

- **Python 3.11+** (for backend services)
  - Download from: https://www.python.org/downloads/
  - Or use package managers:
    - Windows (Chocolatey): `choco install python`
    - Windows (winget): `winget install Python.Python.3.11`
    - macOS (Homebrew): `brew install python@3.11`
    - Ubuntu/Debian: `sudo apt update && sudo apt install python3.11 python3.11-venv`

- **Node.js 18+** (for frontend development)
  - Download from: https://nodejs.org/
  - Or use package managers:
    - Windows (Chocolatey): `choco install nodejs`
    - Windows (winget): `winget install OpenJS.NodeJS`
    - macOS (Homebrew): `brew install node`
    - Ubuntu/Debian: `curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash - && sudo apt-get install -y nodejs`

- **Microsoft Word** (desktop version for add-in testing)
  - Office 365 subscription or standalone Office installation
  - Must be the desktop app (not web version)

- **An LLM provider** (for AI functionality)
  - [Ollama](https://ollama.com/) (local, free, recommended for getting started)
  - Or cloud providers: Groq, OpenRouter, OpenAI, etc.

#### Optional but Recommended

- **Git** (for cloning the repository)
  - Windows: https://git-scm.com/download/win
  - macOS: `brew install git`
  - Ubuntu: `sudo apt install git`

- **Docker** (for containerized deployment)
  - Windows/macOS: https://docs.docker.com/get-docker/
  - Ubuntu: `sudo apt install docker.io docker-compose`

#### Verification

After installation, verify everything works:

```bash
# Python
python --version  # Should show 3.11+
pip --version     # Should work

# Node.js
node --version    # Should show 18+
npm --version     # Should work

# Git (if installed)
git --version

# Docker (if installed)
docker --version
docker compose version
```

#### First Time Setup

1. **Clone the repository:**
   ```bash
   git clone <repository-url>
   cd docpilot
   ```

2. **Configure environment (optional for local LLM):**
   ```bash
   cp .env.example .env
   # Edit .env with your API keys if using cloud LLM providers
   ```

3. **Set up Ollama (recommended for local AI):**
   ```bash
   # Install Ollama from https://ollama.com/
   ollama pull qwen2.5  # Pull a model
   ```

### Option A: Docker (Backend Services Only)

Docker runs the backend services (DOC-MCP and Agent). Frontend must run locally for Word add-in integration.

```bash
# 1. Start backend services
docker compose up --build

# 2. In another terminal, start frontend
cd frontend
npm install
npx office-addin-dev-certs install
npm run dev

# 3. Verify
# Backend services: http://localhost:8000/docs, http://localhost:8001/docs
# Frontend: https://localhost:3000/src/taskpane.html
```

### Option B: Local Development (Recommended)

**Windows:**
```batch
# 1. Configure (optional)
copy .env.example .env

# 2. Start all services
start-dev.bat
```

**Linux/macOS:**
```bash
# 1. Configure (optional)
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
npm install
npx office-addin-dev-certs install
npx http-server . -p 3000 -S -C .office-addin-dev-certs/localhost.crt -K .office-addin-dev-certs/localhost.key --cors
```

**Verify services are running:**
- DOC-MCP Service: http://localhost:8001/docs
- Agent Backend: http://localhost:8000/docs
- Frontend: https://localhost:3000/src/taskpane.html

### Setting Up the Word Add-in

1. Open Microsoft Word
2. Go to **Insert** → **Add-ins** → **My Add-ins** → **Shared Folder** (or use sideloading)
3. For development, use the [Office Add-in Sideloading guide](https://learn.microsoft.com/en-us/office/dev/add-ins/testing/sideload-office-add-ins-for-testing):
   - Place `frontend/manifest.xml` in a shared folder
   - Or use: `cd frontend && npm run start`
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

Execute the AI agent on a document. **Auto mode**: The agent automatically determines the best approach.

```json
{
    "message": "Rewrite this document in a more professional tone",
    "document_base64": "UEsDBBQ...",
    "action": "rewrite",
    "provider_name": "local"
}
```

**Parameters:**
| Field | Type | Description |
|-------|------|-------------|
| `message` | string | User instruction |
| `document_base64` | string? | Base64-encoded .docx file |
| `action` | string? | `rewrite`, `improve`, `tailor_cv`, `generate` (auto-detects if omitted) |
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

## Intelligent Mode Selection (Auto)

DocPilot automatically determines the best approach for each request:

### Preserve Mode (Block-level edits)
**Used when:** User wants to modify specific content while keeping structure
- Examples: "improve grammar", "make more professional", "tailor for job description"
- Behavior: Updates specific text blocks while preserving formatting, styles, and structure
- Heading styles are preserved
- List formatting is maintained  
- Table structure remains unchanged

### Rebuild Mode (Full regeneration)
**Used when:** User wants to generate new content or restructure document
- Examples: "generate a new CV", "rebuild the document from scratch"
- Behavior: Generates completely new document with automatic structuring
- Creates proper heading hierarchy
- Structures content with appropriate styles
- Supports tables and lists

### How it works:
1. **If you specify action**: Message → Rewrite, Improve, Tailor CV → PRESERVE | Generate, Rebuild → REBUILD
2. **If you don't specify action**: Agent uses LLM to classify your intent automatically
3. **Always follows your prompt**: The mode is just implementation detail - your user message is always followed

**No need to choose manually** - the agent handles it intelligently! ✨

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
| Add-in not loading | Check manifest.xml URLs match your dev server. Office add-ins require HTTPS. Use `npx office-addin-dev-certs install` for development. |
| Document not updating | Verify the add-in has ReadWriteDocument permissions |
| Certificate errors in browser | Run `npx office-addin-dev-certs install` to trust the development certificate |
| Python virtualenv issues | Delete `.venv` folder and rerun the start script to recreate it |
| Node.js/npm issues | Clear npm cache: `npm cache clean --force` and reinstall: `rm -rf node_modules && npm install` |
| Permission errors | On Windows, run terminal as Administrator. On Linux/Mac, check file permissions |
| Port already in use | Kill processes using ports 8000, 8001, 3000: `npx kill-port 8000 8001 3000` |
| Ollama model not found | Run `ollama pull qwen2.5` to download the model |
| **SSL: CERTIFICATE_VERIFY_FAILED** (cloud LLM providers fail) | This happens in development environments with SSL verification issues. Set environment variable: `set LLM_VERIFY_SSL=false` (Windows) or `export LLM_VERIFY_SSL=false` (Linux/Mac). This disables SSL verification for external API calls (development only). |

### Common Setup Issues

**SSL Certificate Verification (Development):**
- DocPilot's LLM client verifies SSL certificates by default (secure for production)
- In some development environments, especially on Windows, you may see "SSL: CERTIFICATE_VERIFY_FAILED" when using cloud LLM providers (OpenRouter, Groq, OpenAI)
- **Fix for Development**: Disable SSL verification temporarily:
  ```bash
  # Windows
  set LLM_VERIFY_SSL=false
  npm run dev  # or start-dev.bat
  
  # Linux/Mac
  export LLM_VERIFY_SSL=false
  npm run dev  # or ./start-dev.sh
  ```
- **Production**: Always keep SSL verification enabled (default behavior)

**On Windows:**
- If `python` command not found, use `py` or install Python properly
- For certificate trust issues, run PowerShell as Administrator when installing certs
- For SSL errors with cloud LLMs, use the `LLM_VERIFY_SSL=false` environment variable above

**On macOS/Linux:**
- If permission denied on ports < 1024, use ports > 1024 or run with sudo (not recommended)
- For certificate trust, the `office-addin-dev-certs` should handle it automatically

**For Docker:**
- Ensure Docker Desktop is running
- On Windows, enable file sharing for the project directory
- Clear Docker cache if build fails: `docker system prune -a`

---

## License

MIT
