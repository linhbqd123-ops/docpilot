# Run all DocPilot modules

Quick launcher to start the three development modules together:

- doc-mcp (Java service)
- core-engine (FastAPI Python service)
- desktop (React dev server)

Usage (from repository root):

```bash
python run_all.py
```

Windows (PowerShell):

```powershell
# Use the Python you want (py or python)
py run_all.py
```

What the script does:
- Builds `packages/doc-mcp` if no JAR is present (requires Maven/Java).
- Creates and populates `packages/core-engine/.venv` if missing, then runs `uvicorn main:app --reload --port 8000`.
- Runs `npm install` in `packages/desktop` if needed and starts the dev server with `npm run dev`.

Requirements:
- Java (and Maven to build doc-mcp) for the first module.
- Python 3.11+ (used to create venv and run core-engine).
- Node.js and npm for the desktop.

Stop:
- Press Ctrl+C in the terminal where you ran `run_all.py` to stop all child processes.
