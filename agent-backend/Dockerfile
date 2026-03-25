FROM python:3.12-slim

WORKDIR /app

# Install dependencies for both agent-backend and llm-layer
COPY agent-backend/requirements.txt ./agent-requirements.txt
COPY llm-layer/requirements.txt ./llm-requirements.txt
RUN pip install --no-cache-dir -r agent-requirements.txt -r llm-requirements.txt

# Copy application code
COPY agent-backend/app ./app
COPY llm-layer/llm_core ./llm_core
COPY config ./config

ENV PYTHONPATH=/app

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
