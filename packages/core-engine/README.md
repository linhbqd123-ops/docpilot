# DocPilot Core Engine

Python FastAPI service that acts as the **AI gateway** for the DocPilot desktop app.

- Exposes `/api/chat` (SSE streaming) for the frontend
- Proxies document conversion to the Java doc-processor (internal, port 8080)
- Supports Ollama (default), OpenAI, Anthropic, and Azure OpenAI providers

## Requirements

- Python 3.11+
- [Ollama](https://ollama.com) running locally (for the default local LLM)
- Java doc-processor running on port 8080

## Setup

```bash
cd packages/core-engine

# Create virtual environment
python -m venv .venv
.venv\Scripts\activate        # Windows
# source .venv/bin/activate   # macOS / Linux

# Install dependencies
pip install -r requirements.txt

# Configure
cp .env.example .env
# Edit .env — at minimum set OLLAMA_DEFAULT_MODEL to a model you have pulled

# Run
python main.py
# or: uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Server runs on **http://localhost:8000**.

## Ollama quick-start

```bash
ollama pull llama3.2          # default model
ollama pull mistral           # alternative
```

## API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Health check |
| POST | `/api/chat` | AI chat with SSE streaming |
| POST | `/api/documents/import` | Convert DOCX/PDF → HTML (via Java) |
| POST | `/api/documents/export` | Convert HTML → DOCX (via Java) |

See **http://localhost:8000/docs** for interactive Swagger UI.

## SSE event format (`/api/chat`)

```
data: {"delta": "streaming text chunk"}\n\n
data: {"documentHtml": "<updated doc HTML>"}\n\n   ← only when doc changed
data: [DONE]\n\n
```

Error:
```
data: {"notices": ["Error: ..."]}
```

## Environment variables

See `.env.example` for all options. Key ones:

| Variable | Default | Description |
|----------|---------|-------------|
| `DOC_PROCESSOR_URL` | `http://localhost:8080` | Java service URL |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama URL |
| `OLLAMA_DEFAULT_MODEL` | `llama3.2` | Model to use |
| `OPENAI_API_KEY` | *(empty)* | Required for OpenAI provider |
| `ANTHROPIC_API_KEY` | *(empty)* | Required for Anthropic provider |
