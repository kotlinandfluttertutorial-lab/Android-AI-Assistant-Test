# ============================================================================
# Android AI Assistant — Backend Dockerfile (multi-stage)
# ============================================================================
#
# Stage 1 (builder): Install Python dependencies from requirements.txt and
#                    compile native wheels. Build tools stay in this stage only.
# Stage 2 (production): Slim runtime image — no build tools, no pip, no
#                       setuptools, no package caches.
#
# Build:
#   docker build -t ai-assistant-backend:latest .
#
# Run:
#   docker run --env-file .env -p 8000:8000 ai-assistant-backend:latest
# ============================================================================

# ── Stage 1: builder ────────────────────────────────────────────────────────
FROM python:3.11-slim AS builder

LABEL maintainer="ai-assistant-team"

# Prevent Python from writing .pyc files and buffering stdout/stderr
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /build

# Install system build dependencies required by some Python packages
# (e.g., psycopg2-binary, cryptography, Pillow, pytesseract)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    libffi-dev \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

# Cache-bust ARG — increment when requirements change to force pip re-install.
# chromadb version: 0.5.20 (server + client matched, api/v2 routes working)
ARG CACHE_BUST=20260918-chromadb0520

# Compile all wheels and install into an isolated prefix directory so we can
# copy only the installed packages to the final stage — no pip, no wheel
# cache, no build artefacts carried forward.
# Use PyTorch CPU-only index to avoid downloading multi-GB CUDA wheels.
RUN pip install --upgrade pip \
    && pip install --prefix=/install --no-warn-script-location \
        --extra-index-url https://download.pytorch.org/whl/cpu \
        -r requirements.txt


# ── Stage 2: production ─────────────────────────────────────────────────────
FROM python:3.11-slim AS production

LABEL maintainer="ai-assistant-team" \
      org.opencontainers.image.title="Android AI Assistant Backend" \
      org.opencontainers.image.description="FastAPI backend for the Android AI Assistant" \
      org.opencontainers.image.source="https://github.com/your-org/android-ai-assistant"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app

# Install only the runtime system libraries needed at execution time.
# NO build tools (gcc, make, build-essential, pip, setuptools) are present
# in this stage.
# perl-base is upgraded explicitly to pull in the security patch for
# CVE-2026-13221, CVE-2026-42496, and CVE-2026-8376 (all CRITICAL, fixed in
# 5.40.1-6+deb13u1). perl-base ships in python:3.11-slim but is not needed
# at runtime — upgrading rather than removing it to avoid breaking dpkg
# dependency resolution.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 \
    tesseract-ocr \
    poppler-utils \
    && apt-get upgrade -y --no-install-recommends perl-base \
    && rm -rf /var/lib/apt/lists/*

# Copy installed Python packages from the builder stage.
# pip itself is NOT copied — only the site-packages and scripts.
COPY --from=builder /install /usr/local

# Create a non-root user for security.
# --home /home/appuser ensures $HOME is a real, writable directory (the
# default --system home is /nonexistent, which breaks huggingface_hub cache).
RUN addgroup --system appgroup \
    && adduser --system --ingroup appgroup --home /home/appuser appuser \
    && mkdir -p /home/appuser/.cache \
    && chown -R appuser:appgroup /home/appuser

# Point HuggingFace / sentence-transformers cache to a writable path before
# the model download step so they land in the right layer.
ENV HF_HOME=/home/appuser/.cache/huggingface \
    TRANSFORMERS_CACHE=/home/appuser/.cache/huggingface

# Pre-download the SentenceTransformer embedding model into the image so
# Cloud Run instances never hit HuggingFace Hub at runtime.  Without this,
# every new instance downloads ~90 MB on first use, adding ~17 s of latency
# to the first RAG query and triggering the 20-s embedding timeout.
#
# The download runs as root here so it writes into the system-wide cache dir
# (/home/appuser/.cache) which is already owned by appuser.  The USER appuser
# instruction below switches back to the non-root user for the actual process.
RUN python -c "\
from sentence_transformers import SentenceTransformer; \
SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2'); \
print('Model cached successfully')"

WORKDIR /app

# Copy application source code and Alembic migration files
COPY app/ ./app/
COPY scripts/ ./scripts/
COPY alembic/ ./alembic/
COPY alembic.ini .
# Entrypoint selects API (uvicorn), worker (celery), or reingest based on APP_MODE env var
COPY entrypoint.sh ./entrypoint.sh

# Ensure the non-root user owns the app directory and entrypoint is executable
RUN chown -R appuser:appgroup /app \
    && chown -R appuser:appgroup /home/appuser/.cache \
    && chmod +x /app/entrypoint.sh

# HF_HOME and TRANSFORMERS_CACHE are already set above the model download step.

USER appuser

EXPOSE 8000

# Health check: verify the /health endpoint responds within 10 s
HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

# APP_MODE=api  (default) → uvicorn FastAPI server
# APP_MODE=worker         → celery worker (drains ingestion/notifications/gdpr queues)
ENV APP_MODE=api

CMD ["/app/entrypoint.sh"]
