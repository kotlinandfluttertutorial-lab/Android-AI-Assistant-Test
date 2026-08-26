# ─────────────────────────────────────────────────────────────────────────────
# providers.tf
#
# Declares which Terraform providers we need and pins them to exact versions.
#
# WHY PIN VERSIONS?
#   Provider updates can introduce breaking changes. Pinning ensures every
#   developer and every CI run uses the exact same provider binary.
#   "~> 5.0" means: >= 5.0.0 and < 6.0.0 (minor updates allowed, major blocked).
# ─────────────────────────────────────────────────────────────────────────────

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 5.0"
    }
  }
}

# ─── Primary provider ─────────────────────────────────────────────────────────
# Reads project/region from variables so the same config works for dev + prod.
# Credentials are NOT set here — we use Application Default Credentials (ADC):
#   gcloud auth application-default login
# This means no service account key files ever touch this repository.
provider "google" {
  project = var.project_id
  region  = var.region
}

# google-beta exposes features that are in preview but stable enough to use.
# We use it only for resources that require it (annotated at the call site).
provider "google-beta" {
  project = var.project_id
  region  = var.region
}
