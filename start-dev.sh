#!/bin/bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "===== Starting DocPilot Services ====="
echo

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

# Create venv once (if missing) and install requirements
if [ ! -f "$ROOT/.venv/bin/python" ]; then
    echo -e "${BLUE}[setup]${NC} Creating virtualenv at $ROOT/.venv ..."
    python3 -m venv "$ROOT/.venv"
    echo -e "${BLUE}[setup]${NC} Installing doc-mcp-service requirements..."
    "$ROOT/.venv/bin/pip" install -q -r "$ROOT/doc-mcp-service/requirements.txt"
    echo -e "${BLUE}[setup]${NC} Installing agent-backend requirements..."
    "$ROOT/.venv/bin/pip" install -q -r "$ROOT/agent-backend/requirements.txt"
    echo -e "${BLUE}[setup]${NC} Installing llm-layer requirements..."
    "$ROOT/.venv/bin/pip" install -q -r "$ROOT/llm-layer/requirements.txt"
    echo -e "${GREEN}[setup]${NC} Bootstrap complete."
else
    echo -e "${BLUE}[setup]${NC} Virtualenv found — reusing it."
fi

PYTHON="$ROOT/.venv/bin/python"

echo -e "${BLUE}[1/3]${NC} Starting DOC-MCP Service (port 8001)..."
cd "$ROOT/doc-mcp-service"
$PYTHON -m uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload &
DOC_MCP_PID=$!

sleep 2

echo -e "${BLUE}[2/3]${NC} Starting Agent Backend (port 8000)..."
cd "$ROOT/agent-backend"
PYTHONPATH="$ROOT/llm-layer:$PYTHONPATH" $PYTHON -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload &
AGENT_PID=$!

sleep 2

echo -e "${BLUE}[3/3]${NC} Starting Frontend Dev Server (port 3000)..."
cd "$ROOT/frontend"
npx office-addin-dev-certs install
mkdir -p .office-addin-dev-certs
cp ~/.office-addin-dev-certs/* .office-addin-dev-certs/ 2>/dev/null || true
npx http-server . -p 3000 -S -C .office-addin-dev-certs/localhost.crt -K .office-addin-dev-certs/localhost.key --cors &
FRONTEND_PID=$!
cd "$ROOT"

echo
echo "===== All services started! ====="
echo
echo "  DOC-MCP Service:  http://localhost:8001"
echo "  Agent Backend:     http://localhost:8000"
echo "  Frontend:          https://localhost:3000"
echo
echo "  API Docs:"
echo "    DOC-MCP:  http://localhost:8001/docs"
echo "    Agent:    http://localhost:8000/docs"
echo

echo
echo -e "${GREEN}===== All services started! =====${NC}"
echo
echo "  DOC-MCP Service:  http://localhost:8001"
echo "  Agent Backend:     http://localhost:8000"
echo "  Frontend:          http://localhost:3000"
echo
echo "  API Docs:"
echo "    DOC-MCP:  http://localhost:8001/docs"
echo "    Agent:    http://localhost:8000/docs"
echo
echo "Press Ctrl+C to stop all services"

trap "kill $DOC_MCP_PID $AGENT_PID $FRONTEND_PID 2>/dev/null; exit" SIGINT SIGTERM
wait
