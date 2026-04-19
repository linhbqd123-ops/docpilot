# DocPilot Enhanced Logging System

## Overview

The DocPilot logging system has been enhanced to provide **centralized, structured error tracking** across all three components:
- **core-engine** (Python FastAPI backend)
- **doc-mcp** (Java Spring Boot service)
- **desktop** (TypeScript/React frontend)

All logs are written to the central `/logs` folder in JSONL (JSON Lines) format for easy parsing and debugging.

## Log Files Location

All logs are centralized at: **`/logs/`**

### Log Files

| File | Source | Purpose |
|------|--------|---------|
| `core-engine-info.jsonl` | Python backend | Info events from core-engine API |
| `core-engine-error.jsonl` | Python backend | Error events and exceptions |
| `doc-mcp-info.jsonl` | Java service | Info events from doc-mcp |
| `doc-mcp-error.jsonl` | Java service | Error events and exceptions |
| `desktop-info.jsonl` | React frontend | Info events from desktop app |
| `desktop-error.jsonl` | React frontend | Error events from desktop app |

## Structured Logging Format

Each log line is a JSON object with this structure:

```json
{
  "timestamp": "2026-04-18T02:30:45.123456+00:00",
  "level": "error",
  "module": "core-engine",
  "event": "agent.revision.apply.error",
  "traceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "caller": {
    "func": "apply_agent_revision",
    "file": "agent.py",
    "line": 350
  },
  "error": "Document service returned an error (502)",
  "errorCode": "doc_mcp_unavailable",
  "context": {
    "revisionId": "rev_dec7a9c54045",
    "operation": "apply"
  },
  "exceptionType": "DocMcpResponseError",
  "traceback": "..."
}
```

### Common Fields

- **timestamp**: ISO 8601 timestamp in UTC
- **level**: `info` or `error`
- **module**: Source module (`core-engine`, `doc-mcp`, `desktop`)
- **event**: Event name (e.g., `agent.revision.apply.error`)
- **traceId**: Trace ID for correlating requests across modules
- **caller**: Source code location information
- **exceptionType**: Exception class name (if error)
- **traceback**: Full traceback (if exception)

## Key Events

### Revision Operations

When applying/rejecting/rolling back revisions, the following events are logged:

```python
# Start
log_core_event("agent.revision.apply.start", revisionId=revision_id)

# Success
log_core_event("agent.revision.apply.success", revisionId=revision_id, sessionId=session_id)

# Error (includes context)
log_core_event(
    "agent.error.doc_mcp",
    error="...",
    errorCode="...",
    context={"revisionId": revision_id, "operation": "apply"}
)
```

### Doc-MCP Requests

Every request to the Java service logs:

```python
log_core_event(
    "doc_mcp.request",
    method="POST",
    url="http://localhost:8080/api/revisions/rev_123/apply",
    revisionId="rev_123",
    operation="apply"
)

# Response
log_core_event(
    "doc_mcp.response",
    method="POST",
    statusCode=200,
    revisionId="rev_123",
    operation="apply"
)

# Error
log_core_event(
    "doc_mcp.error",
    method="POST",
    error="Connection refused",
    revisionId="rev_123",
    operation="apply"
)
```

## Trace ID Correlation

All events in a single user action are correlated by `traceId`:

1. **Desktop** creates a trace ID and sends it in `X-DocPilot-Trace-Id` header
2. **core-engine** receives it, propagates to doc-mcp requests
3. **doc-mcp** receives it and includes in its logs
4. All three systems include the trace ID in their logs

This allows you to follow a single revision apply operation across all three components:

```bash
# Find all events for a revision apply operation
grep "rev_dec7a9c54045" logs/*.jsonl | head -20
```

## Debug API Endpoints

The core-engine provides REST APIs to view and inspect logs:

### List all log files
```
GET /api/debug/logs
```
Returns:
```json
{
  "logsRoot": "/path/to/logs",
  "files": [
    {"name": "core-engine-error.jsonl", "size": 12345, "lines": 45},
    {"name": "doc-mcp-error.jsonl", "size": 54321, "lines": 89}
  ],
  "totalFiles": 6
}
```

### Get recent log entries
```
GET /api/debug/logs/{filename}?limit=100&offset=0
```
Returns the last 100 lines from the log file as parsed JSON objects.

Example:
```
GET /api/debug/logs/core-engine-error.jsonl?limit=20&offset=0
```

### Get error summary
```
GET /api/debug/logs/{filename}/errors
```
Returns error types and counts:
```json
{
  "file": "core-engine-error.jsonl",
  "totalLines": 150,
  "errorTypes": {
    "DocMcpResponseError": 45,
    "DocMcpUnavailableError": 23,
    "HTTPException": 12,
    "ValueError": 8
  }
}
```

## How to Investigate apply_revision Error

When a revision apply fails with a 502 error:

### 1. Check Recent Error Logs
```bash
curl http://localhost:8000/api/debug/logs/core-engine-error.jsonl?limit=10
```

### 2. Look for your revision ID
```bash
grep "rev_dec7a9c54045" logs/core-engine-error.jsonl
```

### 3. Check doc-mcp logs
```bash
curl http://localhost:8000/api/debug/logs/doc-mcp-error.jsonl?limit=10
```

### 4. Trace correlation
Find the traceId from the error:
```bash
grep "traceId.*a1b2c3d4" logs/*.jsonl
```
This shows all events across all three modules for that operation.

## Configuration

### Environment Variables

```bash
# Python (core-engine)
# Logs written to: ./logs/core-engine-*.jsonl

# Java (doc-mcp)
# Logs written to: ../../../logs/doc-mcp-*.jsonl (resolved from target folder)
LOG_INFO_FILE=../../../logs/doc-mcp-info.log
LOG_ERROR_FILE=../../../logs/doc-mcp-error.log

# Desktop (React)
# Logs sent to core-engine /api/debug/frontend endpoint
# Written to: ../core-engine/logs/desktop-*.jsonl
```

### Enable/Disable Logging

To disable logging:
1. Comment out `log_core_event()` calls
2. Or set log level in logback-spring.xml to `WARN` or `ERROR`

To enable debug logging:
1. In core-engine: Set Python logging level to `DEBUG`
2. In doc-mcp: Change logback root level to `DEBUG`

## Troubleshooting

### Logs not appearing in /logs folder

1. Check logs are being created:
   ```bash
   ls -lh /logs/
   ```

2. Check file permissions:
   ```bash
   chmod 777 /logs
   ```

3. Check core-engine is running:
   ```bash
   curl http://localhost:8000/docs
   ```

4. Check doc-mcp Java service is running:
   ```bash
   curl http://localhost:8080/health
   ```

### Log files are empty

1. The events may not have occurred yet. Perform an action (e.g., apply a revision)
2. Check if logging code is being executed (add print statements)
3. Check if errors are being caught and not re-raised

### Can't find an error in logs

1. Verify the trace ID is correct
2. Check all log files (error + info)
3. Check if the error occurred on a different day (logs rotate daily)
4. Try broader search: `grep -r "error" logs/`

## Implementation Details

### Python Side (core-engine)

- **debug_logging.py**: Core logging infrastructure
  - `_JsonlWriter`: Thread-safe JSON line writer
  - `log_core_event()`: Log core-engine events
  - `log_docmcp_event()`: Log doc-mcp events
  - `log_desktop_event()`: Log desktop events
  - Exception formatting with traceback capture

- **api/agent.py**: Enhanced revision endpoints
  - `_map_doc_mcp_error()`: Structured error logging
  - Context passing for revision operations
  - Start/success/error events

- **services/doc_mcp_client.py**: Doc-mcp request logging
  - Context parameter in `_request()`
  - Request/response/error logging
  - Revision ID propagation

- **api/debug.py**: Debug API endpoints
  - `/api/debug/logs`: List log files
  - `/api/debug/logs/{filename}`: View log contents
  - `/api/debug/logs/{filename}/errors`: Error summary
  - `/api/debug/doc-mcp`: Accept logs from Java service

### Java Side (doc-mcp)

- **logback-spring.xml**: Logging configuration
  - FILE_INFO appender: Info level logs
  - FILE_ERROR appender: Error level logs
  - Central path: `../../../logs/` (relative to target folder)
  - MDC for trace ID correlation

- **TraceLoggingFilter.java**: HTTP trace logging
  - Already logs all requests/responses
  - Includes trace ID in MDC
  - Format: method, URI, headers, body, status, latency

## Future Enhancements

1. **Log Aggregation**: Ship logs to centralized ELK/Splunk
2. **Real-time Alerts**: Alert on critical errors
3. **Performance Metrics**: Log operation latencies
4. **User Identification**: Track which user triggered errors
5. **Automatic Error Recovery**: Auto-retry on transient failures
6. **Log Retention Policy**: Archive old logs to S3
7. **Dashboard**: Web UI for viewing logs and metrics
