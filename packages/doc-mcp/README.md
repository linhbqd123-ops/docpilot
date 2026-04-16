# DocPilot Doc MCP

`doc-mcp` is the canonical document engine in DocPilot. It owns imported DOCX state, stable anchors, revisions, snapshots, export, and the MCP tool surface used by higher-level agents.

## Responsibilities

- Import DOCX into a canonical component tree.
- Persist sessions, revisions, patches, and registries in SQLite.
- Stage, apply, reject, and roll back revisions.
- Expose AI-facing MCP tools for retrieval and editing workflows.
- Maintain semantic document indexing in Qdrant when enabled.

## Runtime requirements

- JDK 17+
- Maven 3.9+

## Build and run

```powershell
cd packages/doc-mcp
./build.bat
java -jar target/doc-mcp-*.jar
```

## Key environment variables

| Variable | Default | Notes |
| --- | --- | --- |
| `DOC_MCP_DATA_DIR` | `%LOCALAPPDATA%\DocPilot\mcp` on Windows | Base data directory |
| `SQLITE_PATH` | derived from data dir | SQLite database file |
| `PERSONALIZATION_PROVIDER` | `disabled` | Set `qdrant` to enable semantic retrieval |
| `QDRANT_URL` | `http://127.0.0.1:6333` | Qdrant HTTP endpoint |
| `QDRANT_COLLECTION` | `docpilot_personalization` | Shared collection |
| `EMBEDDING_PROVIDER` | `hashing` | Offline-safe default |
| `EMBEDDING_API_URL` | empty | Required for `openai-compatible` |
| `EMBEDDING_MODEL` | empty | Required for `openai-compatible` |
| `EMBEDDING_API_KEY` | empty | Optional bearer auth |
| `LOG_FILE` | `debug/doc-mcp.log` | File path for detailed request and MCP traces |

## Qdrant behavior

When `PERSONALIZATION_PROVIDER=qdrant`:

- the service ensures the collection exists at startup,
- each imported session is chunked from the canonical tree,
- chunks are embedded and upserted into Qdrant,
- session-scoped searches use `points/query`,
- AI-facing tools consume semantic results before lexical fallback.

The default embedding mode is `hashing`, which keeps local development offline and deterministic. Switch to `openai-compatible` if you want higher-quality embeddings from an external endpoint.

## Debug traces

`doc-mcp` now writes detailed HTTP and MCP traces to `packages/doc-mcp/debug/doc-mcp.log` by default.

- Every inbound request records method, path, headers, request body, response body, and latency.
- The log format includes `X-DocPilot-Trace-Id`, so one chat turn can be correlated with the desktop and core-engine debug files.
- Set `LOG_FILE` if you want the trace file written somewhere else.

## Docker with Qdrant

```powershell
cd packages/doc-mcp
docker compose --profile personalization up --build
```

The compose file starts:

- `doc-mcp`
- `qdrant`

The mounted Qdrant config lives in `packaging/qdrant/config.local.yaml`.

## Windows app-image bundle

Build only the app image:

```powershell
./build.bat win-app
```

Build the app image with a bundled Qdrant sidecar:

```powershell
$env:QDRANT_WINDOWS_ZIP = "C:\artifacts\qdrant-x86_64-pc-windows-msvc.zip"
./build.bat win-app --bundle-qdrant
```

After bundling, start the packaged app with:

```powershell
dist\DocPilotMcp\launch-docpilot-with-qdrant.bat
```

That launcher starts Qdrant, waits for health on `127.0.0.1:6333`, and then runs `DocPilotMcp.exe` with semantic retrieval defaults wired in.