output "registry_url" {
  description = "Base URL for docker push/pull. Append /<image-name>:<tag>."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.docker_repo.repository_id}"
}

output "repository_id" {
  description = "Short repository ID (e.g. 'backend')."
  value       = google_artifact_registry_repository.docker_repo.repository_id
}
