FROM python:3.11-slim

# Install ChromaDB — same version as the backend client
RUN pip install --no-cache-dir chromadb==0.5.20

# Create data directory (ephemeral on Cloud Run without a volume)
RUN mkdir -p /tmp/chroma && chmod 777 /tmp/chroma

# Settings via env vars read by chromadb.config.Settings (pydantic BaseSettings)
ENV IS_PERSISTENT=true
ENV PERSIST_DIRECTORY=/tmp/chroma
ENV ANONYMIZED_TELEMETRY=false
ENV ALLOW_RESET=true

EXPOSE 8000

# Use the same entrypoint the official CLI uses: chromadb.app:app
# This is the canonical way to serve chromadb v0.5.x with all v2 routes.
CMD ["python", "-m", "uvicorn", "chromadb.app:app", "--host", "0.0.0.0", "--port", "8000", "--log-level", "info"]
