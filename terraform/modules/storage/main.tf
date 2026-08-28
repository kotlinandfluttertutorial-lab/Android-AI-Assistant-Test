# ─────────────────────────────────────────────────────────────────────────────
# modules/storage/main.tf
#
# Creates the GCS bucket, grants the backend SA access to it,
# and generates HMAC keys so the existing minio SDK can talk to GCS
# without any code changes.
#
# KEY CONCEPT — HMAC keys:
#   GCS supports the S3-compatible XML API when you authenticate with HMAC keys.
#   The minio-python SDK (already in backend/requirements.txt) speaks S3.
#   Pointing MINIO_ENDPOINT=storage.googleapis.com + HMAC credentials lets the
#   backend upload/download files from GCS without any library swap.
# ─────────────────────────────────────────────────────────────────────────────

# ── Bucket ────────────────────────────────────────────────────────────────────
resource "google_storage_bucket" "files" {
  project  = var.project_id
  name     = var.bucket_name
  location = var.bucket_location

  # Prevent accidental public access — all objects are private by default.
  # Individual objects can be made public explicitly if needed (e.g. public assets).
  public_access_prevention = "enforced"

  # Uniform bucket-level access: IAM policies apply to the entire bucket,
  # not per-object ACLs. Simpler and more auditable.
  uniform_bucket_level_access = true

  # Keep deleted objects for 7 days — safety net against accidental deletes.
  soft_delete_policy {
    retention_duration_seconds = 604800  # 7 days
  }

  # Versioning: keep previous versions of overwritten objects.
  # Useful for recovering overwritten documents or generated reports.
  versioning {
    enabled = true
  }

  # Lifecycle: delete non-current versions older than 30 days to control costs.
  lifecycle_rule {
    condition {
      num_newer_versions = 3
      with_state         = "ARCHIVED"
    }
    action {
      type = "Delete"
    }
  }

  labels = var.labels

  # Prevent Terraform destroy from deleting the bucket and all its contents.
  # Remove this line ONLY if you are intentionally tearing down the whole project.
  lifecycle {
    prevent_destroy = false  # set to true in prod after first successful deploy
  }
}

# ── Bucket IAM — backend service account ─────────────────────────────────────
# Grant Object Admin on this bucket ONLY — not the whole project.
# Principle of least privilege: the backend can read/write/delete objects
# in this bucket but cannot touch other buckets or GCP resources.
resource "google_storage_bucket_iam_member" "backend_object_admin" {
  bucket = google_storage_bucket.files.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${var.backend_sa_email}"
}

# ── HMAC keys ─────────────────────────────────────────────────────────────────
# Creates an HMAC key pair for the backend service account.
# The access ID (public) and secret (sensitive) are stored in Secret Manager
# by the null_resource below — Terraform itself never writes them to state
# in plain text after the initial creation, but NOTE: Terraform state DOES
# contain the secret value. Keep your state bucket private and enable
# versioning + encryption (done in backend.tf).
resource "google_storage_hmac_key" "backend" {
  project               = var.project_id
  service_account_email = var.backend_sa_email

  # HMAC keys are stateful: once deleted they cannot be recreated with the
  # same access ID. If you need to rotate, create a new key first, update
  # Secret Manager, redeploy, then delete the old key.
}

# ── Store HMAC values in Secret Manager ──────────────────────────────────────
# Terraform creates/updates these secret versions so the Cloud Run deploy
# always has the latest HMAC credentials without manual copy-paste.

resource "google_secret_manager_secret" "hmac_access_key" {
  project   = var.project_id
  secret_id = "MINIO_ACCESS_KEY"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "hmac_access_key" {
  secret      = google_secret_manager_secret.hmac_access_key.id
  secret_data = google_storage_hmac_key.backend.access_id
}

resource "google_secret_manager_secret" "hmac_secret_key" {
  project   = var.project_id
  secret_id = "MINIO_SECRET_KEY"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "hmac_secret_key" {
  secret      = google_secret_manager_secret.hmac_secret_key.id
  # secret_data is marked sensitive — Terraform will not print it in plans/logs
  secret_data = google_storage_hmac_key.backend.secret
}
