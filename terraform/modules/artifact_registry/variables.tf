variable "project_id" {
  description = "GCP project ID."
  type        = string
}

variable "region" {
  description = "GCP region for the Artifact Registry repository."
  type        = string
}

variable "repo_name" {
  description = "Repository name (appears in the image path)."
  type        = string
  default     = "backend"
}

variable "backend_sa_email" {
  description = "Full email of the backend service account — granted writer access."
  type        = string
}

variable "labels" {
  description = "Labels applied to the repository."
  type        = map(string)
  default     = {}
}
