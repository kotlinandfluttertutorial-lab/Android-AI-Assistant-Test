FROM python:3.11-slim

# Install ChromaDB with the exact same version as the client
RUN pip install --no-cache-dir chromadb==0.5.20

# Create data directory (ephemeral on Cloud Run — data resets on restart)
RUN mkdir -p /data && chmod 777 /data

WORKDIR /app

ENV IS_PERSISTENT=1
ENV ANONYMIZED_TELEMETRY=false
ENV ALLOW_RESET=true
ENV CHROMA_SERVER_HTTP_PORT=8000

EXPOSE 8000

# Use chromadb.server.fastapi which registers /api/v2/* routes
# chromadb.app only registers /api/v1/* routes
CMD ["python", "-m", "uvicorn", "chromadb.server.fastapi:app", "--host", "0.0.0.0", "--port", "8000", "--log-level", "info"]
