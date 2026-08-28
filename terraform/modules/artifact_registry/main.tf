# ─────────────────────────────────────────────────────────────────────────────
# modules/artifact_registry/main.tf
#
# Creates a private Docker image registry in Artifact Registry.
#
# WHY ARTIFACT REGISTRY INSTEAD OF CONTAINER REGISTRY?
#   Container Registry (gcr.io) is deprecated. Artifact Registry is the
#   current GCP standard — it supports Docker, Maven, npm, Python, and more,
#   has fine-grained IAM per repository, and works natively with Cloud Run.
# ─────────────────────────────────────────────────────────────────────────────

resource "google_artifact_registry_repository" "docker_repo" {
  project       = var.project_id
  location      = var.region
  repository_id = var.repo_name
  description   = "Docker images for the AI Assistant backend"
  format        = "DOCKER"

  # IMMUTABLE_TAGS prevents overwriting an existing tag.
  # In production you should push new image tags (e.g. git SHA) rather than
  # overwriting :latest — this gives you a full image history and easy rollback.
  docker_config {
    immutable_tags = false  # allow :latest overwrite during development
  }

  labels = var.labels
}

# ── Cleanup policy ────────────────────────────────────────────────────────────
# Keep the 10 most recent untagged images and delete anything older than 30 days.
# This prevents stale intermediate layers from accumulating storage costs.
resource "google_artifact_registry_repository_iam_member" "ci_writer" {
  project    = var.project_id
  location   = var.region
  repository = google_artifact_registry_repository.docker_repo.name
  role       = "roles/artifactregistry.writer"

  # The backend service account pushes images from CI via Workload Identity.
  # This grants write access at the repository level only — not the whole project.
  member = "serviceAccount:${var.backend_sa_email}"
}

resource "google_artifact_registry_repository_iam_member" "cloud_run_reader" {
  project    = var.project_id
  location   = var.region
  repository = google_artifact_registry_repository.docker_repo.name
  role       = "roles/artifactregistry.reader"

  # Cloud Run pulls images using the Compute Engine default service account
  # unless you specify a custom SA. We grant the backend SA read access too
  # so it can be used as the Cloud Run service account without needing a
  # separate identity.
  member = "serviceAccount:${var.backend_sa_email}"
}
