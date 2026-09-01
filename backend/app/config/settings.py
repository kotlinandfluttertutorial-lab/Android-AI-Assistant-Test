# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : config
# File    : settings.py
# Purpose : Application configuration loaded from environment variables via pydantic-settings
#
# Architecture Layer : Configuration
# Pattern Used       : pydantic-settings Configuration
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Application settings loaded from environment variables.

Uses pydantic-settings so every value can be overridden via environment variables or a
`.env` file placed in the backend/ directory.  See `backend/.env.example` for the full
list of available variables and their descriptions.

Access the singleton settings object via `get_settings()` (cached with @lru_cache so
the environment is only parsed once per process).

Requirements: 20.6
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# Resolve the .env path relative to this file (backend/app/config/settings.py)
# so it is found correctly regardless of the working directory uvicorn is
# launched from.  Falls back to the CWD-relative ".env" when running tests
# that spin up a separate FastAPI instance without the full directory tree.
_ENV_FILE = Path(__file__).resolve().parents[2] / ".env"  # backend/.env


class Settings(BaseSettings):
    """All application configuration loaded from environment variables.

    Variables are case-insensitive; underscores and dots are treated as
    equivalent separators.  A `.env` file in backend/ is read automatically
    at startup (lower priority than actual env vars).
    """

    model_config = SettingsConfigDict(
        env_file=str(_ENV_FILE) if _ENV_FILE.exists() else ".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",  # ignore unknown env vars — keeps deployment flexible
    )

    # -------------------------------------------------------------------------
    # Database / Cache
    # -------------------------------------------------------------------------

    DATABASE_URL: str = Field(
        description="PostgreSQL async connection URL used by SQLAlchemy / asyncpg. "
        "Example: postgresql+asyncpg://user:password@localhost:5432/aiassistant"
    )

    REDIS_URL: str = Field(
        description="Redis connection URL used for caching, rate limiting, and as the "
        "default Celery broker/backend. Example: redis://localhost:6379/0"
    )

    # -------------------------------------------------------------------------
    # JWT / Auth
    # -------------------------------------------------------------------------

    SECRET_KEY: str = Field(
        description="Secret key used to sign and verify JWT tokens. "
        "Must be a cryptographically random string of at least 32 characters. "
        'Generate with: python -c "import secrets; print(secrets.token_hex(32))"'
    )

    JWT_ALGORITHM: str = Field(
        default="HS256",
        description="Algorithm used for JWT signing. HS256 (HMAC-SHA256) is the default.",
    )

    ACCESS_TOKEN_EXPIRE_MINUTES: int = Field(
        default=15,
        description="Lifetime of an access (bearer) token in minutes. Defaults to 15.",
        ge=1,
    )

    REFRESH_TOKEN_EXPIRE_DAYS: int = Field(
        default=30,
        description="Lifetime of a refresh token in days. Defaults to 30.",
        ge=1,
    )

    # Aliases used by the JWT module (Requirements: 1.2, 1.3, 1.4)
    # These allow the JWT handler to reference config using conventional names.
    @property
    def JWT_SECRET_KEY(self) -> str:
        """Alias for SECRET_KEY for use by the JWT handler."""
        return self.SECRET_KEY

    @property
    def JWT_ACCESS_TOKEN_EXPIRE_MINUTES(self) -> int:
        """Alias for ACCESS_TOKEN_EXPIRE_MINUTES for use by the JWT handler."""
        return self.ACCESS_TOKEN_EXPIRE_MINUTES

    @property
    def JWT_REFRESH_TOKEN_EXPIRE_DAYS(self) -> int:
        """Alias for REFRESH_TOKEN_EXPIRE_DAYS for use by the JWT handler."""
        return self.REFRESH_TOKEN_EXPIRE_DAYS

    # -------------------------------------------------------------------------
    # LLM Provider API Keys
    # -------------------------------------------------------------------------

    OPENAI_API_KEY: str = Field(
        default="",
        description="OpenAI API key for GPT-4o and other OpenAI models. "
        "Leave blank to disable the OpenAI provider.",
    )

    GEMINI_API_KEY: str = Field(
        default="",
        description="Google Gemini API key (google-generativeai). "
        "Leave blank to disable the Gemini provider.",
    )

    ANTHROPIC_API_KEY: str = Field(
        default="",
        description="Anthropic API key for Claude 3.5 Sonnet and other Claude models. "
        "Leave blank to disable the Anthropic provider.",
    )

    OLLAMA_BASE_URL: str = Field(
        default="http://localhost:11434",
        description="Base URL of the local Ollama server. "
        "Used to route requests to self-hosted Llama / Mistral models.",
    )

    # -------------------------------------------------------------------------
    # Vector Store (ChromaDB)
    # -------------------------------------------------------------------------

    CHROMA_HOST: str = Field(
        default="chromadb",
        description="Hostname of the ChromaDB server used for vector storage.",
    )

    CHROMA_PORT: int = Field(
        default=8001,
        description="Port of the ChromaDB HTTP server.",
        ge=1,
        le=65535,
    )

    # -------------------------------------------------------------------------
    # Object Storage (MinIO local / GCS production)
    # -------------------------------------------------------------------------

    STORAGE_BACKEND: str = Field(
        default="minio",
        description="Storage backend to use for uploaded documents. "
        "One of: 'minio' (local Docker Compose / S3-compatible) or 'gcs' (Google Cloud Storage). "
        "Set to 'gcs' on Cloud Run — files are stored in GCS_BUCKET_NAME using ADC. "
        "Set to 'minio' for local development with docker-compose.",
    )

    GCS_BUCKET_NAME: str = Field(
        default="",
        description="Google Cloud Storage bucket name for document uploads. "
        "Required when STORAGE_BACKEND=gcs. "
        "The Cloud Run service account must have roles/storage.objectAdmin on this bucket. "
        "Example: android-ai-assistant-89cec-files",
    )

    MINIO_ENDPOINT: str = Field(
        default="localhost:9000",
        description="MinIO server endpoint in host:port format (no scheme). "
        "Only used when STORAGE_BACKEND=minio. "
        "Example: minio.example.com:9000",
    )

    MINIO_ACCESS_KEY: str = Field(
        default="",
        description="MinIO access key (username). Only used when STORAGE_BACKEND=minio.",
    )

    MINIO_SECRET_KEY: str = Field(
        default="",
        description="MinIO secret key (password). Only used when STORAGE_BACKEND=minio.",
    )

    MINIO_BUCKET_NAME: str = Field(
        default="documents",
        description="Name of the MinIO bucket where uploaded documents are stored. "
        "Only used when STORAGE_BACKEND=minio.",
    )

    # -------------------------------------------------------------------------
    # Firebase
    # -------------------------------------------------------------------------

    FIREBASE_CREDENTIALS_PATH: str = Field(
        default="",
        description="Path to the Firebase service account JSON credentials file. "
        "Required for Firebase Admin SDK (push notifications, remote config). "
        "Leave blank to disable Firebase integration.",
    )

    FIREBASE_REMOTE_CONFIG_ENABLED: bool = Field(
        default=False,
        description="Enable Firebase Remote Config for feature flags. "
        "Requires FIREBASE_CREDENTIALS_PATH to be set.",
    )

    # -------------------------------------------------------------------------
    # CORS / Host Security
    # -------------------------------------------------------------------------

    CORS_ORIGINS: list[str] = Field(
        default=["http://localhost:3000"],
        description="List of allowed CORS origins. "
        "In production, replace with your exact frontend URL(s). "
        'Example: ["https://app.example.com"]',
    )

    ALLOWED_HOSTS: list[str] = Field(
        default=["*"],
        description="Allowed Host header values. "
        'Set to your domain(s) in production, e.g. ["api.example.com"].',
    )

    # -------------------------------------------------------------------------
    # File / RAG Settings
    # -------------------------------------------------------------------------

    MAX_FILE_SIZE_MB: int = Field(
        default=50,
        description="Maximum file size (in megabytes) accepted by the document upload endpoint.",
        ge=1,
    )

    RAG_CHUNK_SIZE: int = Field(
        default=512,
        description="Token chunk size used when splitting documents for RAG ingestion.",
        ge=64,
    )

    RAG_CHUNK_OVERLAP: int = Field(
        default=64,
        description="Number of overlapping tokens between consecutive RAG chunks.",
        ge=0,
    )

    RAG_TOP_K: int = Field(
        default=5,
        description="Number of top-K chunks retrieved from ChromaDB for each RAG query.",
        ge=1,
    )

    # -------------------------------------------------------------------------
    # Rate Limiting / Account Lockout
    # -------------------------------------------------------------------------

    RATE_LIMIT_REQUESTS_PER_MINUTE: int = Field(
        default=60,
        description="Maximum number of requests a single authenticated user may make per minute.",
        ge=1,
    )

    RATE_LIMIT_UNAUTH_REQUESTS_PER_MINUTE: int = Field(
        default=20,
        description=(
            "Maximum number of requests an unauthenticated source IP may make per minute. "
            "Applied to all public (unauthenticated) HTTP requests as a second-tier rate limit. "
            "Requirements: 9.11"
        ),
        ge=1,
    )

    DATA_RESIDENCY_REGION: str = Field(
        default="",
        description=(
            "Configured geographic region for data residency enforcement. "
            "When non-empty, write operations (POST, PUT, PATCH, DELETE) are rejected "
            "with HTTP 403 if the X-Client-Region request header is present and does not match. "
            "Leave blank to disable data residency enforcement. "
            "Requirements: 9.7"
        ),
    )

    MAX_REQUEST_BODY_SIZE: int = Field(
        default=1 * 1024 * 1024,  # 1 MiB
        description=(
            "Maximum allowed request body size in bytes for JSON endpoints. "
            "Oversized bodies are rejected with HTTP 413 before reaching Pydantic validation. "
            "File upload endpoints (/documents/upload) are exempt and use MAX_FILE_SIZE_MB. "
            "Default: 1 MiB (1 048 576 bytes)."
        ),
        ge=1_024,  # minimum 1 KiB
    )

    ACCOUNT_LOCKOUT_MAX_ATTEMPTS: int = Field(
        default=5,
        description="Number of failed login attempts before the account is temporarily locked.",
        ge=1,
    )

    ACCOUNT_LOCKOUT_WINDOW_MINUTES: int = Field(
        default=10,
        description="Sliding window (in minutes) within which failed login attempts are counted.",
        ge=1,
    )

    ACCOUNT_LOCKOUT_DURATION_MINUTES: int = Field(
        default=15,
        description="Duration (in minutes) that an account remains locked after exceeding "
        "ACCOUNT_LOCKOUT_MAX_ATTEMPTS.",
        ge=1,
    )

    # -------------------------------------------------------------------------
    # Celery (task queue)
    # -------------------------------------------------------------------------

    CELERY_BROKER_URL: str = Field(
        default="",
        description="Celery broker URL. Defaults to REDIS_URL when left blank. "
        "Example: redis://localhost:6379/1",
    )

    CELERY_RESULT_BACKEND: str = Field(
        default="",
        description="Celery result backend URL. Defaults to REDIS_URL when left blank. "
        "Example: redis://localhost:6379/2",
    )

    # -------------------------------------------------------------------------
    # Logging / Environment
    # -------------------------------------------------------------------------

    LOG_LEVEL: str = Field(
        default="INFO",
        description="Python logging level. One of: DEBUG, INFO, WARNING, ERROR, CRITICAL.",
    )

    ENVIRONMENT: str = Field(
        default="development",
        description="Deployment environment. One of: development, staging, production, test. "
        "Controls security-sensitive defaults (e.g., debug mode, HTTPS-only cookies).",
    )

    # -------------------------------------------------------------------------
    # LLM Fallback and Per-Provider Rate Limits
    # -------------------------------------------------------------------------

    FALLBACK_LLM_PROVIDER: str = Field(
        default="",
        description="LLM provider to fall back to when the primary provider fails. "
        "One of: openai, gemini, claude, ollama, llama, mistral. "
        "Leave blank to disable fallback. "
        "Can also be set via LLM_FALLBACK_PROVIDER (preferred alias per Requirement 26.6).",
    )

    LLM_FALLBACK_PROVIDER: str = Field(
        default="",
        description=(
            "Preferred alias for FALLBACK_LLM_PROVIDER (Requirement 26.6). "
            "LLM provider to use when the primary provider fails. "
            "One of: openai, gemini, claude, ollama, llama, mistral. "
            "When absent or empty the AI Orchestrator returns a structured error "
            "instead of attempting a fallback. Leave blank to disable fallback."
        ),
    )

    @property
    def effective_fallback_provider(self) -> str:
        """Return the effective fallback provider, preferring LLM_FALLBACK_PROVIDER.

        LLM_FALLBACK_PROVIDER (Requirement 26.6) takes precedence over the
        legacy FALLBACK_LLM_PROVIDER alias. Returns an empty string when neither
        is configured, causing the AI Orchestrator to return a structured error
        instead of attempting a fallback.
        """
        return self.LLM_FALLBACK_PROVIDER or self.FALLBACK_LLM_PROVIDER

    DEFAULT_LLM_PROVIDER: str = Field(
        default="gemini",
        description="Default LLM provider used for RAG queries and general completions. "
        "One of: openai, gemini, claude, ollama, llama, mistral. "
        "Defaults to gemini.",
    )

    # Per-provider rate limits (requests per minute, 0 = disabled / unlimited)
    LLM_RATE_LIMIT_OPENAI: int = Field(
        default=60,
        description="Maximum LLM requests per minute for the OpenAI provider. 0 = unlimited.",
        ge=0,
    )

    LLM_RATE_LIMIT_GEMINI: int = Field(
        default=60,
        description="Maximum LLM requests per minute for the Gemini provider. 0 = unlimited.",
        ge=0,
    )

    LLM_RATE_LIMIT_CLAUDE: int = Field(
        default=40,
        description="Maximum LLM requests per minute for the Claude provider. 0 = unlimited.",
        ge=0,
    )

    LLM_RATE_LIMIT_OLLAMA: int = Field(
        default=0,
        description="Maximum LLM requests per minute for the Ollama provider. 0 = unlimited.",
        ge=0,
    )

    LLM_RATE_LIMIT_LLAMA: int = Field(
        default=0,
        description="Maximum LLM requests per minute for the Llama provider. 0 = unlimited.",
        ge=0,
    )

    LLM_RATE_LIMIT_MISTRAL: int = Field(
        default=60,
        description="Maximum LLM requests per minute for the Mistral provider. 0 = unlimited.",
        ge=0,
    )

    # Per-provider maximum output token limits (Requirement 25.5)
    LLM_MAX_OUTPUT_TOKENS_OPENAI: int = Field(
        default=4096,
        description="Maximum output tokens per response for the OpenAI provider. "
        "GPT-4o supports up to 4096 output tokens. 0 = no cap (not recommended).",
        ge=0,
    )

    LLM_MAX_OUTPUT_TOKENS_GEMINI: int = Field(
        default=8192,
        description="Maximum output tokens per response for the Gemini provider. "
        "Gemini 1.5 Pro supports up to 8192 output tokens. 0 = no cap (not recommended).",
        ge=0,
    )

    LLM_MAX_OUTPUT_TOKENS_CLAUDE: int = Field(
        default=8192,
        description="Maximum output tokens per response for the Claude provider. "
        "Claude 3.5 Sonnet supports up to 8192 output tokens. 0 = no cap (not recommended).",
        ge=0,
    )

    LLM_MAX_OUTPUT_TOKENS_OLLAMA: int = Field(
        default=2048,
        description="Maximum output tokens per response for the Ollama provider. "
        "Applies to self-hosted Ollama models. 0 = no cap.",
        ge=0,
    )

    LLM_MAX_OUTPUT_TOKENS_LLAMA: int = Field(
        default=2048,
        description="Maximum output tokens per response for the Llama provider. "
        "Applies to Llama 3.x via local Ollama. 0 = no cap.",
        ge=0,
    )

    LLM_MAX_OUTPUT_TOKENS_MISTRAL: int = Field(
        default=2048,
        description="Maximum output tokens per response for the Mistral provider. "
        "Applies to Mistral via local Ollama. 0 = no cap.",
        ge=0,
    )

    # -------------------------------------------------------------------------
    # Encryption
    # -------------------------------------------------------------------------

    AES_ENCRYPTION_KEY: str = Field(
        default="",
        description="Base64-encoded AES-256 key used to encrypt stored API keys at rest. "
        'Generate with: python -c "import base64, os; print(base64.b64encode(os.urandom(32)).decode())"',
    )

    # -------------------------------------------------------------------------
    # SMTP (email notifications)
    # -------------------------------------------------------------------------

    SMTP_HOST: str = Field(
        default="",
        description="SMTP server hostname for sending transactional emails. "
        "Example: smtp.sendgrid.net",
    )

    SMTP_PORT: int = Field(
        default=587,
        description="SMTP server port. 587 (STARTTLS) is the most common.",
        ge=1,
        le=65535,
    )

    SMTP_USER: str = Field(
        default="",
        description="SMTP username / login. Often an API key for services like SendGrid.",
    )

    SMTP_PASSWORD: str = Field(
        default="",
        description="SMTP password or API key.",
    )

    SMTP_FROM_EMAIL: str = Field(
        default="noreply@example.com",
        description="'From' email address used for all outgoing transactional emails.",
    )

    # -------------------------------------------------------------------------
    # Google OAuth2
    # -------------------------------------------------------------------------

    GOOGLE_CLIENT_ID: str = Field(
        default="",
        description="Google OAuth2 Web client ID. Used as the expected audience when "
        "verifying Google ID tokens on the server. "
        "Obtain from Google Cloud Console → APIs & Services → Credentials → Web application.",
    )

    GOOGLE_ANDROID_CLIENT_ID: str = Field(
        default="",
        description="Google OAuth2 Android client ID. Accepted as an alternative audience "
        "when verifying Google ID tokens (Credential Manager sets aud to the Web client ID, "
        "but this field is checked as a fallback). "
        "Obtain from Google Cloud Console → APIs & Services → Credentials → Android.",
    )

    GOOGLE_CLIENT_SECRET: str = Field(
        default="",
        description="Google OAuth2 client secret. Keep this value secret.",
    )

    # -------------------------------------------------------------------------
    # Security hardening
    # -------------------------------------------------------------------------

    BCRYPT_WORK_FACTOR: int = Field(
        default=12,
        description="bcrypt cost factor (rounds). Higher = slower hashing, more secure. "
        "12 is the recommended minimum; use 14+ for high-security environments.",
        ge=4,
        le=31,
    )

    # -------------------------------------------------------------------------
    # MCP Tool Connector credentials
    # -------------------------------------------------------------------------

    GITHUB_TOKEN: str = Field(
        default="",
        description="GitHub personal access token or OAuth token for MCP GitHub connector.",
    )

    GMAIL_CREDENTIALS_PATH: str = Field(
        default="",
        description="Path to Google OAuth2 credentials JSON for Gmail MCP connector.",
    )

    GDRIVE_CREDENTIALS_PATH: str = Field(
        default="",
        description="Path to Google OAuth2 credentials JSON for Google Drive MCP connector. "
        "Leave blank to reuse GMAIL_CREDENTIALS_PATH.",
    )

    GCAL_CREDENTIALS_PATH: str = Field(
        default="",
        description="Path to Google OAuth2 credentials JSON for Google Calendar MCP connector.",
    )

    SLACK_BOT_TOKEN: str = Field(
        default="",
        description="Slack bot OAuth token (xoxb-...) for the MCP Slack connector.",
    )

    JIRA_BASE_URL: str = Field(
        default="",
        description="Jira Cloud base URL, e.g. https://yourorg.atlassian.net",
    )

    JIRA_USER_EMAIL: str = Field(
        default="",
        description="Jira account email for Basic Auth.",
    )

    JIRA_API_TOKEN: str = Field(
        default="",
        description="Jira API token for Basic Auth.",
    )

    NOTION_TOKEN: str = Field(
        default="",
        description="Notion integration secret token for the MCP Notion connector.",
    )

    FIGMA_ACCESS_TOKEN: str = Field(
        default="",
        description="Figma personal access token for the MCP Figma connector.",
    )

    # -------------------------------------------------------------------------
    # Observability
    # -------------------------------------------------------------------------

    PROMETHEUS_ENABLED: bool = Field(
        default=True,
        description="Expose Prometheus metrics at /metrics. "
        "Disable in environments where metrics are scraped another way.",
    )

    LOKI_URL: str = Field(
        default="",
        description="Grafana Loki push endpoint URL for structured log shipping. "
        "Example: http://loki:3100/loki/api/v1/push. Leave blank to disable.",
    )

    OTEL_ENABLED: bool = Field(
        default=True,
        description="Enable OpenTelemetry distributed tracing. "
        "Set to false in unit test environments to avoid OTLP connection attempts.",
    )

    OTEL_SERVICE_NAME: str = Field(
        default="ai-assistant-backend",
        description="Service name attached to every span in Cloud Trace / Jaeger.",
    )

    OTEL_EXPORTER_OTLP_ENDPOINT: str = Field(
        default="",
        description="gRPC OTLP endpoint for span export. "
        "Example (local Jaeger): http://jaeger:4317. "
        "Leave blank to export to Cloud Trace via Application Default Credentials.",
    )

    # -------------------------------------------------------------------------
    # Differential Privacy
    # -------------------------------------------------------------------------

    DP_EPSILON: float = Field(
        default=1.0,
        description=(
            "Default differential-privacy epsilon (ε) for the Laplace noise mechanism "
            "applied to memory embeddings before ChromaDB writes. "
            "Smaller values give stronger privacy guarantees; larger values preserve more "
            "embedding utility. Recommended range: 0.1 (strong) – 10.0 (weak). "
            "Can be overridden at runtime via the Redis key 'dp:epsilon' "
            "(set through PUT /admin/privacy/epsilon). "
            "Requirements: 37.1, 37.8"
        ),
        ge=0.1,
        le=10.0,
    )

    # -------------------------------------------------------------------------
    # Derived properties (not env vars — computed at startup)
    # -------------------------------------------------------------------------

    @property
    def celery_broker(self) -> str:
        """Celery broker URL, falling back to REDIS_URL when not explicitly set."""
        return self.CELERY_BROKER_URL or self.REDIS_URL

    @property
    def celery_backend(self) -> str:
        """Celery result backend URL, falling back to REDIS_URL when not explicitly set."""
        return self.CELERY_RESULT_BACKEND or self.REDIS_URL

    @field_validator("LOG_LEVEL")
    @classmethod
    def validate_log_level(cls, value: str) -> str:
        allowed = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}
        upper = value.upper()
        if upper not in allowed:
            raise ValueError(f"LOG_LEVEL must be one of {allowed}, got {value!r}")
        return upper

    @field_validator("ENVIRONMENT")
    @classmethod
    def validate_environment(cls, value: str) -> str:
        allowed = {"development", "staging", "production", "test"}
        lower = value.lower()
        if lower not in allowed:
            raise ValueError(f"ENVIRONMENT must be one of {allowed}, got {value!r}")
        return lower


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return the cached application settings singleton.

    The first call parses the environment (and optional .env file); subsequent
    calls return the already-parsed object without re-reading the environment.

    Usage::

        from app.config.settings import get_settings

        settings = get_settings()
        print(settings.DATABASE_URL)
    """
    return Settings()  # type: ignore[call-arg]  # required fields supplied via env
