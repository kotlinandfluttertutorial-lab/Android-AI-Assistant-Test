# ─────────────────────────────────────────────────────────────────────────────
# variables.tf
#
# All input variables for the root module.
# Values are NOT set here — they come from:
#   1. environments/dev/terraform.tfvars   (for dev)
#   2. environments/prod/terraform.tfvars  (for prod)
#   3. -var flags on the CLI
#   4. TF_VAR_* environment variables in CI
#
# Sensitive variables (like API keys) should NEVER appear in tfvars files.
# They are managed in GCP Secret Manager and passed to Cloud Run at runtime.
# ─────────────────────────────────────────────────────────────────────────────

# ── Project ───────────────────────────────────────────────────────────────────

variable "project_id" {
  description = "GCP project ID — must already exist with billing enabled."
  type        = string
}

variable "project_number" {
  description = "GCP project number (numeric). Used for Workload Identity paths."
  type        = string
}

variable "region" {
  description = "GCP region for all regional resources."
  type        = string
  default     = "asia-south1"
}

# ── Environment ───────────────────────────────────────────────────────────────

variable "environment" {
  description = "Deployment environment label: dev | prod"
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be 'dev' or 'prod'."
  }
}

# ── Service account ───────────────────────────────────────────────────────────

variable "backend_sa_name" {
  description = "Short name of the backend service account (without @project.iam...)."
  type        = string
  default     = "ai-assistant-backend"
}

# ── Storage ───────────────────────────────────────────────────────────────────

variable "storage_bucket_name" {
  description = "GCS bucket name. Must be globally unique. Defaults to <project_id>-files."
  type        = string
  default     = ""  # resolved in main.tf using locals
}

variable "storage_location" {
  description = "GCS bucket location. Defaults to the same region as other resources."
  type        = string
  default     = ""  # resolved in main.tf using locals
}

# ── Artifact Registry ─────────────────────────────────────────────────────────

variable "artifact_repo_name" {
  description = "Name of the Artifact Registry Docker repository."
  type        = string
  default     = "backend"
}

# ── Cloud Run — FastAPI backend ───────────────────────────────────────────────

variable "backend_image" {
  description = "Full Docker image path for the FastAPI backend (without tag)."
  type        = string
  # Example: asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api
}

variable "backend_image_tag" {
  description = "Image tag to deploy. Set to a git SHA in CI for immutable deploys."
  type        = string
  default     = "latest"
}

variable "backend_min_instances" {
  description = "Minimum Cloud Run instances. 0 = scale to zero (cheapest)."
  type        = number
  default     = 0
}

variable "backend_max_instances" {
  description = "Maximum Cloud Run instances. Hard cap to control costs."
  type        = number
  default     = 2
}

variable "backend_cpu" {
  description = "CPU allocated to each Cloud Run instance."
  type        = string
  default     = "1"
}

variable "backend_memory" {
  description = "Memory allocated to each Cloud Run instance."
  type        = string
  default     = "1Gi"
}

variable "backend_concurrency" {
  description = "Max concurrent requests per instance before Cloud Run scales out."
  type        = number
  default     = 40
}

# ── Cloud Run — ChromaDB ──────────────────────────────────────────────────────

variable "chroma_image" {
  description = "ChromaDB Docker image."
  type        = string
  default     = "chromadb/chroma:1.5.9"
}

variable "chroma_min_instances" {
  description = "ChromaDB minimum instances. 0 = scale to zero."
  type        = number
  default     = 0
}

variable "chroma_max_instances" {
  description = "ChromaDB maximum instances."
  type        = number
  default     = 1
}

# ── Runtime environment variables ─────────────────────────────────────────────
# Non-secret values passed directly to Cloud Run as env vars.
# Secret values (API keys, DB passwords) are pulled from Secret Manager
# inside the cloud_run module — they are NOT variables here.

variable "default_llm_provider" {
  description = "Default LLM provider: gemini | openai | claude"
  type        = string
  default     = "gemini"
}

variable "llm_fallback_provider" {
  description = "Fallback LLM provider if default fails."
  type        = string
  default     = "openai"
}

variable "llm_max_tokens_openai" {
  description = "Max output tokens for OpenAI calls (cost control)."
  type        = number
  default     = 2048
}

variable "llm_max_tokens_gemini" {
  description = "Max output tokens for Gemini calls."
  type        = number
  default     = 4096
}

variable "llm_max_tokens_claude" {
  description = "Max output tokens for Claude calls."
  type        = number
  default     = 2048
}

variable "google_client_id" {
  description = "Google OAuth client ID for web (non-secret, safe to store in tfvars)."
  type        = string
  default     = ""
}

variable "google_android_client_id" {
  description = "Google OAuth client ID for Android."
  type        = string
  default     = ""
}

# ── Workload Identity Federation ──────────────────────────────────────────────

variable "github_org" {
  description = "GitHub organisation or username that owns the repo."
  type        = string
  default     = "kotlinandfluttertutorial-lab"
}

variable "github_repo" {
  description = "GitHub repository name."
  type        = string
  default     = "Android-AI-Assistant-Test"
}
