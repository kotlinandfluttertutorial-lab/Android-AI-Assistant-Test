variable "project_id" {
  description = "GCP project ID."
  type        = string
}

variable "project_number" {
  description = "GCP project number (numeric). Used to build the WIF provider path."
  type        = string
}

variable "backend_sa_name" {
  description = "Short service account name (the part before @project.iam...)."
  type        = string
  default     = "ai-assistant-backend"
}

variable "github_org" {
  description = "GitHub organisation or username that owns the repository."
  type        = string
}

variable "github_repo" {
  description = "GitHub repository name (without the org prefix)."
  type        = string
}
