# DocPilot

DocPilot is a document editing stack for AI-assisted DOCX workflows. The repo is split into a canonical document engine, a FastAPI orchestration layer, and a React desktop-style web client.

## Purpose

- Import DOCX into a stable canonical tree with anchors that survive editing.
- Let AI agents propose structured edits instead of blind text replacement.
- Keep revisions reviewable, revertible, and exportable back to DOCX.


## Architecture

| Module | Path | Responsibility |
| --- | --- | --- |
| Doc MCP | `packages/doc-mcp` | Canonical DOCX session engine, SQLite persistence, revision pipeline, MCP tools |
| Core Engine | `packages/core-engine` | FastAPI backend for chat, provider routing, key management, and document orchestration |
| Desktop | `packages/desktop` | Vite + React UI for chat, review, library, and document workspace |

## Local setup

### 1. Start `doc-mcp`

Requirements:

- JDK 17+
- Maven 3.9+

Run:

```powershell
cd packages/doc-mcp
./build.bat
java -jar target/doc-mcp-*.jar
```

By default the service runs with SQLite persistence.

### 2. Start `core-engine`

Requirements:

- Python 3.11+

Run:

```powershell
cd packages/core-engine
python -m venv .venv
.\.venv\Scripts\Activate.ps1 or .venv\Scripts\activate (cmd)
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

Create `.env` from `.env.example` if you need provider keys or a non-default `DOC_MCP_URL`.

### 3. Start `desktop`

Requirements:

- Node.js 20+
- npm 10+

Run:

```powershell
cd packages/desktop
npm install
npm run dev
```

The UI expects the FastAPI backend at `http://localhost:8000` unless overridden with `VITE_DOCPILOT_API_BASE_URL`.

## Notes

`doc-mcp` runs without external vector stores.

## Windows bundle guide

The current Windows-native bundle target is `doc-mcp`. The React UI is still a web app build, not a single-shell Electron or Tauri executable.

### Build the app image only

```powershell
cd packages/doc-mcp
./build.bat win-app
```

Call `./build.bat win-app` to create the app image.

## Module docs

- `packages/doc-mcp/README.md`
- `packages/core-engine/README.md`
- `packages/desktop/README.md`