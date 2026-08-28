output "backend_sa_email" {
  description = "Full email of the backend service account."
  value       = google_service_account.backend.email
}

output "backend_sa_name" {
  description = "Full resource name of the service account."
  value       = google_service_account.backend.name
}

output "wif_provider" {
  description = "Full WIF provider resource name. Set as GCP_WIF_PROVIDER in GitHub Secrets."
  value       = google_iam_workload_identity_pool_provider.github_oidc.name
}

output "wif_pool_name" {
  description = "Full WIF pool resource name."
  value       = google_iam_workload_identity_pool.github.name
}
