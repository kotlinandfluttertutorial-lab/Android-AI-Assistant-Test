output "backend_url" {
  description = "Public HTTPS URL of the FastAPI backend service."
  value       = google_cloud_run_v2_service.backend.uri
}

output "chroma_url" {
  description = "Internal HTTPS URL of the ChromaDB service."
  value       = google_cloud_run_v2_service.chromadb.uri
}

output "backend_service_name" {
  description = "Cloud Run service name for the backend."
  value       = google_cloud_run_v2_service.backend.name
}

output "chroma_service_name" {
  description = "Cloud Run service name for ChromaDB."
  value       = google_cloud_run_v2_service.chromadb.name
}
