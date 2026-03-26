@echo off
REM DocPilot: idempotent dev starter (creates .venv once and reuses it)
echo ===== Starting DocPilot Services =====
echo.

set ROOT=%~dp0

REM Create a virtualenv once (if missing) and install requirements
if not exist "%ROOT%.venv\Scripts\python.exe" (
	echo [setup] Creating virtualenv at %ROOT%.venv ...
	python -m venv "%ROOT%.venv"
	echo [setup] Installing doc-mcp-service requirements...
	"%ROOT%.venv\Scripts\pip.exe" install -r "%ROOT%doc-mcp-service\requirements.txt"
	echo [setup] Installing agent-backend requirements...
	"%ROOT%.venv\Scripts\pip.exe" install -r "%ROOT%agent-backend\requirements.txt"
	echo [setup] Installing llm-layer requirements...
	"%ROOT%.venv\Scripts\pip.exe" install -r "%ROOT%llm-layer\requirements.txt"
	echo [setup] Bootstrap complete.
) else (
	echo [setup] Virtualenv found at %ROOT%.venv — reusing it.
)

echo.
echo [1/3] Starting DOC-MCP Service (port 8001)...
start "DOC-MCP Service" cmd /c "cd /d %ROOT%doc-mcp-service && "%ROOT%.venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload"

timeout /t 2 /nobreak >nul

echo [2/3] Starting Agent Backend (port 8000)...
start "Agent Backend" cmd /c "cd /d %ROOT%agent-backend && (if defined PYTHONPATH (set PYTHONPATH=%ROOT%llm-layer;%PYTHONPATH%) else (set PYTHONPATH=%ROOT%llm-layer)) && echo Starting uvicorn with auto-reload... && "%ROOT%.venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload --reload-include *.py 2>agent_backend_error.log || (echo Agent backend failed to start. Check agent_backend_error.log for details && pause)"

timeout /t 2 /nobreak >nul

echo [3/3] Starting Frontend Dev Server (port 3000)...
powershell -Command "mkdir %ROOT%frontend\.office-addin-dev-certs -ErrorAction SilentlyContinue; Copy-Item \"$env:USERPROFILE\.office-addin-dev-certs\*\" \"%ROOT%frontend\.office-addin-dev-certs\\\""
start "DocPilot Frontend" cmd /c "cd /d %ROOT%frontend && npx office-addin-dev-certs install && npx http-server . -p 3000 -S -C .office-addin-dev-certs/localhost.crt -K .office-addin-dev-certs/localhost.key --cors"

echo.
echo ===== All services started! =====
echo.
echo   DOC-MCP Service:  http://localhost:8001
echo   Agent Backend:     http://localhost:8000
echo   Frontend:          https://localhost:3000
echo.
echo   API Docs:
echo     DOC-MCP:  http://localhost:8001/docs
echo     Agent:    http://localhost:8000/docs
echo.
pause
