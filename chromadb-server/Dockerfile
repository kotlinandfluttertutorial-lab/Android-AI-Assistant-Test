FROM python:3.11-slim

# Pin fastapi to a version compatible with chromadb 0.5.20's route registration.
# FastAPI >=0.115 changed include_router internals, breaking chromadb's
# server route setup (routes land in _IncludedRouter but never mount).
RUN pip install --no-cache-dir \
    "fastapi==0.112.4" \
    "chromadb==0.5.20"

RUN mkdir -p /tmp/chroma && chmod 777 /tmp/chroma

# Settings via env vars
ENV IS_PERSISTENT=true
ENV PERSIST_DIRECTORY=/tmp/chroma
ENV ANONYMIZED_TELEMETRY=false
ENV ALLOW_RESET=true

EXPOSE 8000

CMD ["python", "-m", "uvicorn", "chromadb.app:app", "--host", "0.0.0.0", "--port", "8000", "--log-level", "info"]
