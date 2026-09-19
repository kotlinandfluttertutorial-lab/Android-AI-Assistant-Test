FROM python:3.11-slim

# Install ChromaDB — same version as the backend client
RUN pip install --no-cache-dir chromadb==0.5.20

# Create data directory (ephemeral on Cloud Run without a volume)
RUN mkdir -p /tmp/chroma && chmod 777 /tmp/chroma

WORKDIR /app

# Copy the server wrapper that exposes FastAPI v2 routes
COPY server.py /app/server.py

ENV ANONYMIZED_TELEMETRY=false
ENV ALLOW_RESET=true
ENV PERSIST_DIRECTORY=/tmp/chroma

EXPOSE 8000

# Use our wrapper — exposes chromadb.server.fastapi which registers /api/v2/* routes
CMD ["python", "-m", "uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8000", "--log-level", "info"]
