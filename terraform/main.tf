# ─────────────────────────────────────────────────────────────────────────────
# main.tf
#
# Root module — orchestrates all child modules.
#
# Think of this file as the wiring diagram. Each module block says:
#   "go run this module and pass it these inputs"
# The modules themselves contain the actual resource definitions.
# ─────────────────────────────────────────────────────────────────────────────

# ── Locals ────────────────────────────────────────────────────────────────────
# Computed values derived from variables. Defined once, used everywhere.

locals {
  # Bucket name defaults to <project_id>-files when not explicitly set
  bucket_name = var.storage_bucket_name != "" ? var.storage_bucket_name : "${var.project_id}-files"

  # Storage location defaults to the same region as everything else
  bucket_location = var.storage_location != "" ? var.storage_location : var.region

  # Full service account email — constructed from the short name
  backend_sa_email = "${var.backend_sa_name}@${var.project_id}.iam.gserviceaccount.com"

  # Full image reference used by Cloud Run
  backend_image_full = "${var.backend_image}:${var.backend_image_tag}"

  # Tags applied to every resource for cost tracking and filtering
  common_labels = {
    project     = var.project_id
    environment = var.environment
    managed-by  = "terraform"
  }
}

# ── Enable required GCP APIs ──────────────────────────────────────────────────
# These are the same APIs you enabled manually in Step 2 of CLOUD_RUN_DEPLOYMENT.md.
# Terraform now owns them — if someone disables an API, the next apply re-enables it.

resource "google_project_service" "required_apis" {
  for_each = toset([
    "run.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
    "storage.googleapis.com",
    "secretmanager.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",   # required for Workload Identity
    "cloudresourcemanager.googleapis.com",
  ])

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false  # don't disable APIs when terraform destroy runs
}

# ── Module: IAM ───────────────────────────────────────────────────────────────
# Creates the backend service account and grants it the roles it needs.
# Nothing else creates IAM resources — centralised in one module.

module "iam" {
  source = "./modules/iam"

  project_id      = var.project_id
  project_number  = var.project_number
  backend_sa_name = var.backend_sa_name
  github_org      = var.github_org
  github_repo     = var.github_repo

  depends_on = [google_project_service.required_apis]
}

# ── Module: Artifact Registry ─────────────────────────────────────────────────
# Creates the Docker image registry where CI pushes built images.

module "artifact_registry" {
  source = "./modules/artifact_registry"

  project_id  = var.project_id
  region      = var.region
  repo_name   = var.artifact_repo_name
  labels      = local.common_labels

  depends_on = [google_project_service.required_apis]
}

# ── Module: Storage ───────────────────────────────────────────────────────────
# Creates the GCS bucket for documents, audio, and generated files.
# Also creates the HMAC keys the minio SDK uses to talk to GCS.

module "storage" {
  source = "./modules/storage"

  project_id       = var.project_id
  bucket_name      = local.bucket_name
  bucket_location  = local.bucket_location
  backend_sa_email = local.backend_sa_email
  labels           = local.common_labels

  # Storage module depends on IAM so the service account exists before we
  # grant it bucket-level permissions
  depends_on = [module.iam]
}

# ── Module: Cloud Run ─────────────────────────────────────────────────────────
# Deploys the FastAPI backend and ChromaDB as Cloud Run services.

module "cloud_run" {
  source = "./modules/cloud_run"

  project_id       = var.project_id
  region           = var.region
  environment      = var.environment
  backend_sa_email = local.backend_sa_email

  # Backend service
  backend_image       = local.backend_image_full
  backend_min         = var.backend_min_instances
  backend_max         = var.backend_max_instances
  backend_cpu         = var.backend_cpu
  backend_memory      = var.backend_memory
  backend_concurrency = var.backend_concurrency

  # ChromaDB service
  chroma_image = var.chroma_image
  chroma_min   = var.chroma_min_instances
  chroma_max   = var.chroma_max_instances

  # Non-secret environment variables
  bucket_name              = local.bucket_name
  default_llm_provider     = var.default_llm_provider
  llm_fallback_provider    = var.llm_fallback_provider
  llm_max_tokens_openai    = var.llm_max_tokens_openai
  llm_max_tokens_gemini    = var.llm_max_tokens_gemini
  llm_max_tokens_claude    = var.llm_max_tokens_claude
  google_client_id         = var.google_client_id
  google_android_client_id = var.google_android_client_id

  labels = local.common_labels

  depends_on = [module.iam, module.artifact_registry]
}
