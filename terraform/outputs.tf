# ─────────────────────────────────────────────────────────────────────────────
# outputs.tf
#
# Values Terraform prints after a successful apply.
# Also used by other Terraform configurations that reference this one as a
# remote state data source (e.g. a separate database Terraform config).
# ─────────────────────────────────────────────────────────────────────────────

output "backend_service_url" {
  description = "Public HTTPS URL of the FastAPI Cloud Run service."
  value       = module.cloud_run.backend_url
}

output "chroma_service_url" {
  description = "Internal HTTPS URL of the ChromaDB Cloud Run service."
  value       = module.cloud_run.chroma_url
}

output "artifact_registry_url" {
  description = "Base URL of the Artifact Registry repo for Docker pushes."
  value       = module.artifact_registry.registry_url
}

output "storage_bucket_name" {
  description = "Name of the GCS bucket used for file storage."
  value       = module.storage.bucket_name
}

output "backend_sa_email" {
  description = "Email of the backend service account."
  value       = module.iam.backend_sa_email
}

output "wif_provider" {
  description = "Full Workload Identity Federation provider name for GitHub Actions secrets."
  value       = module.iam.wif_provider
}

# ── Convenience block printed after every apply ───────────────────────────────
output "next_steps" {
  description = "Quick reference for values needed after apply."
  value = <<-EOT

    ── Apply complete ────────────────────────────────────────────────────
    Backend URL  : ${module.cloud_run.backend_url}
    ChromaDB URL : ${module.cloud_run.chroma_url}
    Bucket       : ${module.storage.bucket_name}
    Registry     : ${module.artifact_registry.registry_url}

    ── GitHub Actions secrets ────────────────────────────────────────────
    GCP_PROJECT_ID      = ${var.project_id}
    GCP_REGION          = ${var.region}
    GCP_WIF_PROVIDER    = ${module.iam.wif_provider}
    GCP_SERVICE_ACCOUNT = ${module.iam.backend_sa_email}
    CLOUD_RUN_SERVICE_URL = ${module.cloud_run.backend_url}
    ─────────────────────────────────────────────────────────────────────

  EOT
}
