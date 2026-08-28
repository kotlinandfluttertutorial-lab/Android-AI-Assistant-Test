variable "project_id" {
  description = "GCP project ID."
  type        = string
}

variable "region" {
  description = "GCP region to deploy Cloud Run services."
  type        = string
}

variable "environment" {
  description = "Deployment environment: dev | prod"
  type        = string
}

variable "backend_sa_email" {
  description = "Service account email used as the Cloud Run service identity."
  type        = string
}

# ── Backend ───────────────────────────────────────────────────────────────────

variable "backend_image" {
  description = "Full Docker image reference including tag."
  type        = string
}

variable "backend_min" {
  description = "Minimum backend instances (0 = scale to zero)."
  type        = number
  default     = 0
}

variable "backend_max" {
  description = "Maximum backend instances."
  type        = number
  default     = 2
}

variable "backend_cpu" {
  description = "CPU per backend instance."
  type        = string
  default     = "1"
}

variable "backend_memory" {
  description = "Memory per backend instance."
  type        = string
  default     = "1Gi"
}

variable "backend_concurrency" {
  description = "Max concurrent requests per backend instance."
  type        = number
  default     = 40
}

# ── ChromaDB ──────────────────────────────────────────────────────────────────

variable "chroma_image" {
  description = "ChromaDB Docker image."
  type        = string
  default     = "chromadb/chroma:1.5.9"
}

variable "chroma_min" {
  description = "Minimum ChromaDB instances."
  type        = number
  default     = 0
}

variable "chroma_max" {
  description = "Maximum ChromaDB instances."
  type        = number
  default     = 1
}

# ── Runtime config ────────────────────────────────────────────────────────────

variable "bucket_name" {
  description = "GCS bucket name passed as MINIO_BUCKET_NAME env var."
  type        = string
}

variable "default_llm_provider" {
  type    = string
  default = "gemini"
}

variable "llm_fallback_provider" {
  type    = string
  default = "openai"
}

variable "llm_max_tokens_openai" {
  type    = number
  default = 2048
}

variable "llm_max_tokens_gemini" {
  type    = number
  default = 4096
}

variable "llm_max_tokens_claude" {
  type    = number
  default = 2048
}

variable "google_client_id" {
  type    = string
  default = ""
}

variable "google_android_client_id" {
  type    = string
  default = ""
}

variable "labels" {
  description = "Labels applied to Cloud Run services."
  type        = map(string)
  default     = {}
}
