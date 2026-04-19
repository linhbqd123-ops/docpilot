# DocPilot Doc MCP

`doc-mcp` is the canonical document engine in DocPilot. It owns imported DOCX state, stable anchors, revisions, snapshots, export, and the MCP tool surface used by higher-level agents.

## Responsibilities

- Import DOCX into a canonical component tree.
- Persist sessions, revisions, patches, and registries in SQLite.
- Stage, apply, reject, and roll back revisions.
	- Expose AI-facing MCP tools for retrieval and editing workflows.

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
| `LOG_INFO_FILE` | `logs/doc-mcp-info.log` | File path for detailed request and MCP info traces |
| `LOG_ERROR_FILE` | `logs/doc-mcp-error.log` | File path for error traces |

## Notes

This repository does not include external vector store integrations.

## Traces and logs

`doc-mcp` writes structured traces into the centralized `logs/` folder by default.

- Info traces: `logs/doc-mcp-info.log` — request/response details, timings, and useful context.
- Error traces: `logs/doc-mcp-error.log` — final error records and stack traces.
- The log format includes `X-DocPilot-Trace-Id` so a single chat turn can be correlated with desktop and core-engine traces.
- Override paths using `LOG_INFO_FILE` and `LOG_ERROR_FILE` environment variables.

## Docker

Run `doc-mcp` with Docker:

```powershell
cd packages/doc-mcp
docker compose up --build
```

## Windows app-image bundle

Build only the app image:

```powershell
./build.bat win-app
```

Build the app image:

```powershell
./build.bat win-app
```