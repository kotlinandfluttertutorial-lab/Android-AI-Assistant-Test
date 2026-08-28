# ─────────────────────────────────────────────────────────────────────────────
# modules/iam/main.tf
#
# Creates the backend service account and grants it exactly the permissions
# it needs — nothing more.
#
# KEY CONCEPT — Principle of Least Privilege:
#   Every GCP service account should have only the roles required for its job.
#   Broad roles like roles/editor or roles/owner are dangerous because a
#   compromised credential can do anything in the project. Instead we grant:
#     - roles/run.invoker       → call other Cloud Run services internally
#     - roles/secretmanager.secretAccessor → read secrets at startup
#     - roles/artifactregistry.writer      → push Docker images from CI
#     - roles/run.developer                → deploy new Cloud Run revisions
#
# KEY CONCEPT — Workload Identity Federation (WIF):
#   Traditional approach: download a service account JSON key, store it as a
#   GitHub secret, use it in CI. Problem: that JSON key is a long-lived
#   credential. If it leaks, an attacker has permanent access.
#
#   WIF approach: GitHub Actions gets a short-lived OIDC token from GitHub.
#   GCP exchanges it for a short-lived GCP access token. No JSON key ever
#   exists. The token expires in 1 hour. Breach surface is near zero.
# ─────────────────────────────────────────────────────────────────────────────

# ── Service account ───────────────────────────────────────────────────────────
resource "google_service_account" "backend" {
  project      = var.project_id
  account_id   = var.backend_sa_name
  display_name = "AI Assistant Backend SA"
  description  = "Used by Cloud Run backend and GitHub Actions CI/CD"
}

# ── Project-level role bindings ───────────────────────────────────────────────
# These roles apply project-wide. Bucket-level and repo-level roles are
# granted in their respective modules (storage, artifact_registry).

locals {
  project_roles = [
    "roles/run.developer",              # deploy Cloud Run services
    "roles/run.invoker",                # call internal Cloud Run services
    "roles/secretmanager.secretAccessor", # read secrets at runtime
    "roles/iam.serviceAccountUser",     # act-as this SA (required for Cloud Run jobs)
    "roles/logging.logWriter",          # write structured logs to Cloud Logging
    "roles/monitoring.metricWriter",    # write metrics to Cloud Monitoring
    "roles/cloudtrace.agent",           # write traces to Cloud Trace
  ]
}

resource "google_project_iam_member" "backend_roles" {
  for_each = toset(local.project_roles)

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.backend.email}"
}

# ── Workload Identity Federation pool ────────────────────────────────────────
resource "google_iam_workload_identity_pool" "github" {
  project                   = var.project_id
  workload_identity_pool_id = "github-actions"
  display_name              = "GitHub Actions"
  description               = "Allows GitHub Actions to impersonate GCP service accounts"

  # disabled = false means the pool is active
}

# ── WIF OIDC provider ─────────────────────────────────────────────────────────
resource "google_iam_workload_identity_pool_provider" "github_oidc" {
  project                            = var.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub OIDC"

  # Map GitHub token claims to GCP attributes.
  # assertion.repository → "owner/repo" e.g. "kotlinandfluttertutorial-lab/Android-AI-Assistant-Test"
  attribute_mapping = {
    "google.subject"             = "assertion.sub"
    "attribute.repository"       = "assertion.repository"
    "attribute.repository_owner" = "assertion.repository_owner"
  }

  # Attribute condition: only tokens from YOUR GitHub org/user are accepted.
  # This prevents any other GitHub repo from impersonating your SA.
  attribute_condition = "assertion.repository_owner == '${var.github_org}'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

# ── Allow the specific repo to impersonate the backend SA ────────────────────
resource "google_service_account_iam_member" "wif_binding" {
  service_account_id = google_service_account.backend.name
  role               = "roles/iam.workloadIdentityUser"

  # principalSet matches any workflow in the specified repository.
  # If you change the repo name or move orgs, update var.github_org / var.github_repo.
  member = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_org}/${var.github_repo}"
}
