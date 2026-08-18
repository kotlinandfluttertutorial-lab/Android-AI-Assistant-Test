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
RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 \
    tesseract-ocr \
    poppler-utils \
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

WORKDIR /app

# Copy application source code
COPY app/ ./app/

# Ensure the non-root user owns the app directory
RUN chown -R appuser:appgroup /app

# Point HuggingFace / sentence-transformers cache to a writable path.
# HF_HOME overrides the default ~/.cache/huggingface location, so model
# downloads work even if $HOME resolution is unexpected.
ENV HF_HOME=/home/appuser/.cache/huggingface \
    TRANSFORMERS_CACHE=/home/appuser/.cache/huggingface

USER appuser

EXPOSE 8000

# Health check: verify the /health endpoint responds within 10 s
HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
