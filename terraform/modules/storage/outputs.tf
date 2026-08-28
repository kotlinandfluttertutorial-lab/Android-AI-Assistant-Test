output "bucket_name" {
  description = "Name of the created GCS bucket."
  value       = google_storage_bucket.files.name
}

output "bucket_url" {
  description = "gs:// URL of the bucket."
  value       = google_storage_bucket.files.url
}

output "hmac_access_id" {
  description = "HMAC access ID (public part). Use as MINIO_ACCESS_KEY."
  value       = google_storage_hmac_key.backend.access_id
}

# The HMAC secret is sensitive — Terraform will mask it in plan/apply output.
# It is stored in Secret Manager automatically by this module.
output "hmac_secret_secret_manager_name" {
  description = "Secret Manager resource name holding the HMAC secret."
  value       = google_secret_manager_secret.hmac_secret_key.name
}
