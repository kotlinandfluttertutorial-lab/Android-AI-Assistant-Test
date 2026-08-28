variable "project_id" {
  description = "GCP project ID."
  type        = string
}

variable "bucket_name" {
  description = "Globally unique GCS bucket name."
  type        = string
}

variable "bucket_location" {
  description = "GCS bucket location (region or multi-region e.g. ASIA)."
  type        = string
}

variable "backend_sa_email" {
  description = "Full email of the backend service account. Granted objectAdmin on the bucket."
  type        = string
}

variable "labels" {
  description = "Labels applied to the bucket."
  type        = map(string)
  default     = {}
}
