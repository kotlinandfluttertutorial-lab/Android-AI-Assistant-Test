# ─────────────────────────────────────────────────────────────────────────────
# modules/cloud_run/main.tf
#
# Deploys two Cloud Run services:
#   1. ai-assistant-backend  — FastAPI backend (public HTTPS)
#   2. chromadb              — Vector store (internal only, not public)
#
# KEY CONCEPT — Cloud Run revision model:
#   Every deploy creates a new revision. Traffic is routed to the latest
#   revision by default. You can split traffic between revisions for
#   canary deployments (e.g. 10% to new, 90% to old).
#
# KEY CONCEPT — Secret Manager references in Cloud Run:
#   Secrets are NOT passed as plain env vars. Cloud Run fetches them from
#   Secret Manager at container startup using the service account identity.
#   Format: { secret: "SECRET_NAME", version: "latest" }
#   The container sees them as regular env vars — no code changes needed.
# ─────────────────────────────────────────────────────────────────────────────

# ── Data: look up the ChromaDB URL after it's deployed ───────────────────────
# ChromaDB is deployed first so the backend can reference its URL.
# We use a depends_on in the backend resource to enforce this ordering.

# ── Service 1: ChromaDB (internal) ────────────────────────────────────────────
resource "google_cloud_run_v2_service" "chromadb" {
  project  = var.project_id
  name     = "chromadb"
  location = var.region

  # ingress = INGRESS_TRAFFIC_INTERNAL_ONLY means this service is only
  # reachable from other Cloud Run services in the same project.
  # It cannot be called from the public internet. This is the correct
  # security posture for a database-like service.
  ingress = "INGRESS_TRAFFIC_INTERNAL_ONLY"

  template {
    service_account = var.backend_sa_email

    scaling {
      min_instance_count = var.chroma_min
      max_instance_count = var.chroma_max
    }

    containers {
      image = var.chroma_image

      ports {
        container_port = 8001
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        # cpu_idle = true → CPU is only allocated while a request is in flight.
        # Combined with min_instances=0, this means zero cost when idle.
        cpu_idle = true
      }

      # ChromaDB stores its data in /chroma/chroma inside the container.
      # Cloud Run filesystem is ephemeral — data is lost on each new revision.
      # For a portfolio project this is acceptable: re-index on redeploy.
      # For production: mount a Cloud Storage FUSE volume here.
      env {
        name  = "IS_PERSISTENT"
        value = "FALSE"
      }

      env {
        name  = "ANONYMIZED_TELEMETRY"
        value = "FALSE"
      }
    }

    labels = var.labels
  }

  labels = var.labels
}

# ── Service 2: FastAPI backend (public) ───────────────────────────────────────
resource "google_cloud_run_v2_service" "backend" {
  project  = var.project_id
  name     = "ai-assistant-backend"
  location = var.region

  # Public ingress — Firebase JWT authentication is enforced inside the app.
  ingress = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = var.backend_sa_email

    scaling {
      min_instance_count = var.backend_min
      max_instance_count = var.backend_max
    }

    containers {
      image = var.backend_image

      ports {
        container_port = 8000
      }

      resources {
        limits = {
          cpu    = var.backend_cpu
          memory = var.backend_memory
        }
        cpu_idle          = true   # CPU throttled when not handling requests
        startup_cpu_boost = true   # extra CPU during cold start to reduce latency
      }

      # ── Non-secret environment variables ─────────────────────────────────
      env {
        name  = "ENVIRONMENT"
        value = var.environment
      }
      env {
        name  = "LOG_LEVEL"
        value = "INFO"
      }
      env {
        name  = "MINIO_ENDPOINT"
        value = "storage.googleapis.com"
      }
      env {
        name  = "MINIO_BUCKET_NAME"
        value = var.bucket_name
      }
      env {
        name  = "CHROMA_HOST"
        # Strip https:// from the ChromaDB URL — the backend expects host only
        value = replace(google_cloud_run_v2_service.chromadb.uri, "https://", "")
      }
      env {
        name  = "CHROMA_PORT"
        value = "8001"
      }
      env {
        name  = "DEFAULT_LLM_PROVIDER"
        value = var.default_llm_provider
      }
      env {
        name  = "LLM_FALLBACK_PROVIDER"
        value = var.llm_fallback_provider
      }
      env {
        name  = "LLM_MAX_OUTPUT_TOKENS_OPENAI"
        value = tostring(var.llm_max_tokens_openai)
      }
      env {
        name  = "LLM_MAX_OUTPUT_TOKENS_GEMINI"
        value = tostring(var.llm_max_tokens_gemini)
      }
      env {
        name  = "LLM_MAX_OUTPUT_TOKENS_CLAUDE"
        value = tostring(var.llm_max_tokens_claude)
      }
      env {
        name  = "GOOGLE_CLIENT_ID"
        value = var.google_client_id
      }
      env {
        name  = "GOOGLE_ANDROID_CLIENT_ID"
        value = var.google_android_client_id
      }
      env {
        name  = "PROMETHEUS_ENABLED"
        value = "true"
      }

      # ── Secret environment variables (pulled from Secret Manager) ─────────
      # Each secret_env_var block tells Cloud Run to inject the secret value
      # as an environment variable at container startup.
      # The service account must have roles/secretmanager.secretAccessor.
      env {
        name = "SECRET_KEY"
        value_source {
          secret_key_ref {
            secret  = "SECRET_KEY"
            version = "latest"
          }
        }
      }
      env {
        name = "AES_ENCRYPTION_KEY"
        value_source {
          secret_key_ref {
            secret  = "AES_ENCRYPTION_KEY"
            version = "latest"
          }
        }
      }
      env {
        name = "DATABASE_URL"
        value_source {
          secret_key_ref {
            secret  = "DATABASE_URL"
            version = "latest"
          }
        }
      }
      env {
        name = "REDIS_URL"
        value_source {
          secret_key_ref {
            secret  = "REDIS_URL"
            version = "latest"
          }
        }
      }
      env {
        name = "MINIO_ACCESS_KEY"
        value_source {
          secret_key_ref {
            secret  = "MINIO_ACCESS_KEY"
            version = "latest"
          }
        }
      }
      env {
        name = "MINIO_SECRET_KEY"
        value_source {
          secret_key_ref {
            secret  = "MINIO_SECRET_KEY"
            version = "latest"
          }
        }
      }
      env {
        name = "OPENAI_API_KEY"
        value_source {
          secret_key_ref {
            secret  = "OPENAI_API_KEY"
            version = "latest"
          }
        }
      }
      env {
        name = "GEMINI_API_KEY"
        value_source {
          secret_key_ref {
            secret  = "GEMINI_API_KEY"
            version = "latest"
          }
        }
      }
      env {
        name = "ANTHROPIC_API_KEY"
        value_source {
          secret_key_ref {
            secret  = "ANTHROPIC_API_KEY"
            version = "latest"
          }
        }
      }

      # ── Liveness / startup probe ──────────────────────────────────────────
      # Cloud Run replaces the default TCP probe with an HTTP check against /health.
      # If /health returns non-2xx three times in a row, Cloud Run restarts the container.
      startup_probe {
        http_get {
          path = "/health"
          port = 8000
        }
        initial_delay_seconds = 10
        timeout_seconds       = 5
        period_seconds        = 10
        failure_threshold     = 5
      }

      liveness_probe {
        http_get {
          path = "/health"
          port = 8000
        }
        initial_delay_seconds = 0
        timeout_seconds       = 5
        period_seconds        = 30
        failure_threshold     = 3
      }
    }

    # Max concurrency per instance — how many parallel requests one container handles
    max_instance_request_concurrency = var.backend_concurrency

    labels = var.labels
  }

  # Route 100% of traffic to the latest revision on every deploy.
  # For canary deployments, split this across two revision tags.
  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }

  labels = var.labels

  depends_on = [google_cloud_run_v2_service.chromadb]
}

# ── IAM: allow public unauthenticated access to the backend ───────────────────
# Cloud Run blocks all traffic unless you explicitly allow it.
# We allow allUsers because the app uses Firebase JWT auth internally.
resource "google_cloud_run_v2_service_iam_member" "backend_public" {
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.backend.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}
