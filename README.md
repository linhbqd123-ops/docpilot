# DocPilot

DocPilot is a document editing stack for AI-assisted DOCX workflows. The repo is split into a canonical document engine, a FastAPI orchestration layer, and a React desktop-style web client.

## Purpose

- Import DOCX into a stable canonical tree with anchors that survive editing.
- Let AI agents propose structured edits instead of blind text replacement.
- Keep revisions reviewable, revertible, and exportable back to DOCX.
- Add semantic retrieval on top of the canonical document model with Qdrant.

## Architecture

| Module | Path | Responsibility |
| --- | --- | --- |
| Doc MCP | `packages/doc-mcp` | Canonical DOCX session engine, SQLite persistence, revision pipeline, MCP tools, Qdrant indexing/search |
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

By default the service runs with SQLite persistence and semantic retrieval disabled.

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

## Qdrant semantic retrieval

`doc-mcp` owns semantic indexing because it already owns the canonical document tree, anchors, revisions, and AI-facing retrieval tools.

Key behavior:

- Reindex on DOCX import.
- Reindex after revision apply.
- Reindex after rollback.
- Use semantic matches first in `answer_about_document` and `locate_relevant_context`, then fall back to lexical matches.

Important environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `PERSONALIZATION_PROVIDER` | `disabled` | Set to `qdrant` to enable semantic retrieval |
| `QDRANT_URL` | `http://127.0.0.1:6333` | Qdrant base URL |
| `QDRANT_COLLECTION` | `docpilot_personalization` | Shared collection name |
| `EMBEDDING_PROVIDER` | `hashing` | `hashing` for offline local vectors, `openai-compatible` for external embeddings |
| `EMBEDDING_API_URL` | empty | Required for `openai-compatible` |
| `EMBEDDING_MODEL` | empty | Required for `openai-compatible` |
| `EMBEDDING_API_KEY` | empty | Optional bearer token for embedding endpoint |

For local Docker usage:

```powershell
cd packages/doc-mcp
docker compose --profile personalization up --build
```

## Windows bundle guide

The current Windows-native bundle target is `doc-mcp` plus a bundled Qdrant sidecar. The React UI is still a web app build, not a single-shell Electron or Tauri executable.

### Build the app image only

```powershell
cd packages/doc-mcp
./build.bat win-app
```

### Build the app image with bundled Qdrant

Option A: point to a local Qdrant Windows zip.

```powershell
$env:QDRANT_WINDOWS_ZIP = "C:\artifacts\qdrant-x86_64-pc-windows-msvc.zip"
cd packages/doc-mcp
./build.bat win-app --bundle-qdrant
```

Option B: let the build script download the zip.

```powershell
$env:QDRANT_DOWNLOAD_URL = "https://github.com/qdrant/qdrant/releases/download/vX.Y.Z/qdrant-x86_64-pc-windows-msvc.zip"
cd packages/doc-mcp
./build.bat win-app --bundle-qdrant
```

Output:

- `packages/doc-mcp/dist/DocPilotMcp/DocPilotMcp.exe`
- `packages/doc-mcp/dist/DocPilotMcp/qdrant/...`
- `packages/doc-mcp/dist/DocPilotMcp/launch-docpilot-with-qdrant.bat`

Use the launcher script to start the bundled Qdrant process, wait for health, then run `DocPilotMcp.exe` with semantic retrieval enabled.

## Module docs

- `packages/doc-mcp/README.md`
- `packages/core-engine/README.md`
- `packages/desktop/README.md`