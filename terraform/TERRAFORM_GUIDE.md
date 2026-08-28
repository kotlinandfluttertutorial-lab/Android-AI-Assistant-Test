# Phase 7 — Terraform (Infrastructure as Code)

> **Learning goal:** Understand why IaC exists, how Terraform works,
> and how to use it to manage the GCP infrastructure for this project.
>
> **Career connection:** Every DevOps, SRE, and platform engineer role
> asks about IaC. Terraform is the industry standard.

---

## 1. Concept — What Is Infrastructure as Code?

Before IaC, infrastructure was configured manually:
- Click through the GCP console to create a Cloud Run service
- Run `gcloud` commands and copy the output into a doc
- Repeat for every new environment

**Problems with manual infrastructure:**
- It drifts — someone makes a change in the console and nobody knows
- It's not reproducible — spinning up a new environment takes days
- It's not auditable — there's no git history of who changed what

**IaC solves this by treating infrastructure like code:**
- Every resource is declared in a `.tf` file
- Changes go through git and code review
- Any environment can be recreated from scratch in minutes
- `git blame` shows you who changed a firewall rule and when

```
Manual approach:              IaC approach:
  GCP Console click             Write .tf file
  → might work                  → terraform plan (preview)
  → might drift                 → terraform apply (execute)
  → hard to reproduce           → git commit (audit trail)
  → no review process           → pull request (code review)
```

---

## 2. Why Terraform Specifically?

There are several IaC tools. Here's why Terraform is the right choice:

| Tool | Approach | Best for |
|------|----------|----------|
| **Terraform** | Declarative HCL, multi-cloud | Industry standard, works on AWS/GCP/Azure |
| Pulumi | Imperative (real code) | Teams that prefer TypeScript/Python syntax |
| CDK for Terraform | TypeScript/Python → Terraform | Bridge between Pulumi and Terraform |
| Deployment Manager | GCP-only YAML | Legacy GCP projects |
| Ansible | Procedural, server config | VM provisioning, not cloud resources |

Terraform's key advantage: **declarative**. You describe the desired end state,
not the steps to get there. Terraform figures out what to create, update, or delete.

```hcl
# You write: "I want a Cloud Run service with these properties"
resource "google_cloud_run_v2_service" "backend" {
  name     = "ai-assistant-backend"
  location = "asia-south1"
  ...
}

# Terraform figures out: does it exist? Create it.
# Does it exist with different settings? Update it.
# Was it removed from the config? Delete it.
```

---

## 3. Architecture — How This Project's Terraform Is Structured

```
terraform/
├── providers.tf          ← which providers to use (google, google-beta)
├── backend.tf            ← where to store state (GCS bucket)
├── variables.tf          ← all input variable declarations
├── main.tf               ← root module — wires all child modules together
├── outputs.tf            ← values printed after apply
├── .gitignore            ← never commit .terraform/ or *.tfstate
│
├── modules/
│   ├── iam/              ← service account + Workload Identity
│   ├── artifact_registry/← Docker image registry
│   ├── storage/          ← GCS bucket + HMAC keys
│   └── cloud_run/        ← FastAPI + ChromaDB services
│
└── environments/
    ├── dev/terraform.tfvars   ← dev-specific values
    └── prod/terraform.tfvars  ← prod-specific values
```

### Why modules?

Each module is a reusable, self-contained piece:
- `modules/iam` owns all IAM resources — one place to audit permissions
- `modules/storage` owns the bucket — delete this module to remove all storage resources
- `modules/cloud_run` owns both services — easy to add a third service later

The root `main.tf` is the orchestrator — it calls modules and passes values between them.

### Data flow between modules

```
variables.tf (inputs)
      │
      ▼
main.tf
  ├── module.iam
  │     └── outputs: backend_sa_email, wif_provider
  │                        │
  ├── module.artifact_registry ◄─ backend_sa_email
  │     └── outputs: registry_url
  │
  ├── module.storage ◄───────── backend_sa_email
  │     └── outputs: bucket_name
  │                        │
  └── module.cloud_run ◄─── backend_sa_email, bucket_name
        └── outputs: backend_url, chroma_url
                        │
                        ▼
                  outputs.tf (printed after apply)
```

---

## 4. Key Concepts

### State file

Terraform's source of truth. Records every resource it has created:
- What the resource ID is
- What properties it has
- What the last-known values were

```
Local state (bad):     terraform.tfstate on your machine
                       → only you can run terraform
                       → lost if your machine dies

Remote state (good):   GCS bucket android-ai-assistant-89cec-tfstate
                       → any machine or CI runner can apply
                       → state locking prevents two applies running at once
                       → versioned — you can recover from a bad apply
```

**Why the state bucket is the ONE thing you create manually:**
Terraform cannot create the bucket it needs to store its own state —
that's a chicken-and-egg problem. Create it once with `gsutil mb`, then
Terraform manages everything else.

### Plan vs Apply

```
terraform plan    → read current state + desired config
                  → calculate the diff
                  → print what WOULD change — nothing happens yet

terraform apply   → same as plan, then asks for confirmation
                  → executes the changes against GCP
                  → updates the state file
```

Always run `plan` before `apply` in production. Review the diff carefully —
a `~ update in-place` is safe, a `-/+ destroy and recreate` may cause downtime.

### `depends_on` and implicit dependencies

Terraform builds a dependency graph automatically from references:

```hcl
# This creates an IMPLICIT dependency:
# cloud_run module uses backend_sa_email from iam module
# → Terraform knows IAM must complete before cloud_run starts

module "cloud_run" {
  backend_sa_email = module.iam.backend_sa_email  # ← implicit dep
}
```

Use explicit `depends_on` only when Terraform can't infer the dependency:

```hcl
module "storage" {
  depends_on = [module.iam]  # ← explicit: SA must exist before bucket IAM
}
```

### `for_each` — avoid repetition

Instead of writing 7 identical IAM binding resources:

```hcl
# Without for_each — 7 repeated blocks
resource "google_project_iam_member" "role_1" { role = "roles/run.developer" ... }
resource "google_project_iam_member" "role_2" { role = "roles/secretmanager.secretAccessor" ... }
# ...

# With for_each — one block, handles all 7
locals {
  project_roles = ["roles/run.developer", "roles/secretmanager.secretAccessor", ...]
}

resource "google_project_iam_member" "backend_roles" {
  for_each = toset(local.project_roles)
  role     = each.value
  ...
}
```

### `locals` — computed values

Locals are values derived from variables. They prevent duplication:

```hcl
locals {
  # Used in 4 different places — defined once
  backend_sa_email = "${var.backend_sa_name}@${var.project_id}.iam.gserviceaccount.com"
}
```

### Sensitive outputs

Mark outputs containing secrets so Terraform never prints them:

```hcl
output "hmac_secret" {
  value     = google_storage_hmac_key.backend.secret
  sensitive = true  # masked in plan/apply output, still readable from state
}
```

---

## 5. Implementation — Step-by-Step First Run

### Prerequisites

```powershell
# Install Terraform (Windows)
winget install HashiCorp.Terraform
terraform version   # needs >= 1.6.0

# Authenticate to GCP
gcloud auth application-default login
# This creates ~/.config/gcloud/application_default_credentials.json
# Terraform reads it automatically — no service account key needed locally
```

### One-time: create the state bucket

```powershell
$gcloud  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$gsutil  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gsutil.cmd"
$PROJECT = "android-ai-assistant-89cec"
$REGION  = "asia-south1"

# Create the state bucket
& $gsutil mb -l $REGION gs://$PROJECT-tfstate

# Enable versioning so you can recover from a corrupted state
& $gsutil versioning set on gs://$PROJECT-tfstate

# Restrict to project members only
& $gsutil iam ch -d allUsers gs://$PROJECT-tfstate
```

### First apply — dev environment

```powershell
cd terraform

# Download providers and configure the remote backend
terraform init -backend-config="prefix=terraform/state/dev"

# Preview what will be created (~25 resources)
terraform plan -var-file="environments/dev/terraform.tfvars"

# Apply — type 'yes' when prompted
terraform apply -var-file="environments/dev/terraform.tfvars"
```

Terraform prints the outputs block at the end:
```
backend_service_url   = "https://ai-assistant-backend-xxxxx.run.app"
chroma_service_url    = "https://chromadb-xxxxx.run.app"
artifact_registry_url = "asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend"
storage_bucket_name   = "android-ai-assistant-89cec-files"
```

### Switching to prod

```powershell
# Re-init with the prod state prefix
terraform init -reconfigure -backend-config="prefix=terraform/state/prod"

terraform plan  -var-file="environments/prod/terraform.tfvars"
terraform apply -var-file="environments/prod/terraform.tfvars"
```

### Updating a resource (e.g. increase max instances)

```hcl
# environments/prod/terraform.tfvars
backend_max_instances = 3   # was 2
```

```powershell
terraform plan -var-file="environments/prod/terraform.tfvars"
# Shows: ~ google_cloud_run_v2_service.backend will be updated in-place
# No destroy, no downtime

terraform apply -var-file="environments/prod/terraform.tfvars"
```

### Tear down dev (cost saving)

```powershell
terraform destroy -var-file="environments/dev/terraform.tfvars"
# Deletes all resources created by this config
# State bucket and its contents are NOT deleted (they're not managed by this config)
```

---

## 6. Common Terraform Commands

| Command | What it does |
|---------|-------------|
| `terraform init` | Download providers, configure backend |
| `terraform plan` | Preview changes — safe, read-only |
| `terraform apply` | Apply changes to GCP |
| `terraform destroy` | Delete all managed resources |
| `terraform show` | Print current state in human-readable form |
| `terraform output` | Print output values |
| `terraform state list` | List all resources in state |
| `terraform state show <resource>` | Show full details of one resource |
| `terraform import <resource> <id>` | Import an existing resource into state |
| `terraform fmt` | Format all `.tf` files consistently |
| `terraform validate` | Check config for syntax errors |
| `terraform graph` | Output a DOT graph of the dependency tree |

---

## 7. Debug — Common Failures

### "Error: googleapi: Error 409: Already exists"

A resource with that name already exists in GCP but is not in Terraform state.
Solution: import it.

```powershell
# Example: import an existing service account
terraform import module.iam.google_service_account.backend \
  projects/android-ai-assistant-89cec/serviceAccounts/ai-assistant-backend@android-ai-assistant-89cec.iam.gserviceaccount.com
```

### "Error: No valid credential sources found"

Terraform can't find GCP credentials.

```powershell
gcloud auth application-default login
# Then retry
```

### "Error 403: Permission denied on resource project"

The account running Terraform doesn't have sufficient project-level permissions.
For initial setup, your personal Google account (`kotlinfiroj@gmail.com`) needs
`roles/editor` or `roles/owner` on the project.

### "Backend configuration changed"

You ran `terraform init` with a different prefix.

```powershell
terraform init -reconfigure -backend-config="prefix=terraform/state/dev"
```

### State lock error

Two applies tried to run simultaneously. The lock is held in GCS.

```powershell
# Find the lock info in the state bucket
gsutil cat gs://android-ai-assistant-89cec-tfstate/terraform/state/dev/default.tflock

# Force-unlock ONLY if you are certain no other apply is running
terraform force-unlock <LOCK_ID>
```

---

## 8. Interview Questions

**Q1: What is the difference between `terraform plan` and `terraform apply`?**

`plan` is a dry run — it reads the current state from the backend, compares
it to the desired config, and prints a diff. Nothing changes in GCP.
`apply` executes that diff after asking for confirmation. Always run `plan`
first in production to review changes before they happen.

---

**Q2: Why store Terraform state remotely instead of locally?**

Local state breaks team collaboration — two people running `apply`
simultaneously corrupt the state file. Remote state in GCS provides:
- A single source of truth for the whole team and CI
- State locking (one apply at a time)
- Versioning (recover from accidents)
- Access control (only authorised accounts can read state, which may contain secrets)

---

**Q3: What is the difference between a Terraform resource and a data source?**

A `resource` creates and manages a GCP object — Terraform owns its lifecycle.
A `data` source reads an existing object without managing it. Example:

```hcl
# resource: Terraform creates this bucket
resource "google_storage_bucket" "files" { ... }

# data source: Terraform reads an existing secret — doesn't own it
data "google_secret_manager_secret_version" "db_url" {
  secret = "DATABASE_URL"
}
```

Use data sources to reference things created outside of Terraform (e.g. a
Neon database connection string that was set up manually).

---

**Q4: What is `terraform import` and when do you need it?**

`import` brings an existing GCP resource under Terraform management without
recreating it. You need it when:
- A resource was created manually before Terraform was introduced
- A `terraform apply` partially failed after creating a resource
- You migrated from a different IaC tool

After importing, Terraform tracks the resource in state and manages future
changes declaratively.

---

**Q5: How does Terraform handle secrets? What should you never do?**

Terraform should **never** contain secret values directly in `.tf` files or
`.tfvars` files. Those files are committed to git.

Correct approaches:
1. Pass secrets via environment variables: `TF_VAR_my_secret=value terraform apply`
2. Read secrets from Secret Manager using a `data` source at apply time
3. Let Terraform generate credentials (like HMAC keys) and immediately push
   them to Secret Manager — as this project's `modules/storage` does

**Never do:** `api_key = "sk-..."` in any `.tf` or `.tfvars` file.

---

**Q6: What is `for_each` and why is it better than `count`?**

Both create multiple instances of a resource. The difference:
- `count` identifies instances by index (0, 1, 2...). If you remove item 0,
  Terraform renumbers everything — causing unexpected destroys and recreates.
- `for_each` identifies instances by a stable key (e.g. role name). Removing
  one entry only destroys that specific resource.

For IAM bindings, service accounts, and any list where order might change,
always use `for_each`.

---

## 9. Production Considerations

**State encryption:** GCS encrypts data at rest by default. For extra
security, enable customer-managed encryption keys (CMEK) on the state bucket.

**Terraform in CI:** Never run `terraform apply` manually in production.
Add a GitHub Actions job that runs on pushes to `main`:

```yaml
- name: Terraform Plan
  run: terraform plan -var-file="environments/prod/terraform.tfvars" -out=tfplan

- name: Terraform Apply (on main only)
  if: github.ref == 'refs/heads/main'
  run: terraform apply tfplan
```

**State isolation:** Dev and prod use different state prefixes in the same
bucket. An alternative is separate state buckets — stricter isolation but
more overhead.

**`prevent_destroy`:** Set `lifecycle { prevent_destroy = true }` on the
production GCS bucket after the first deploy. This stops `terraform destroy`
from deleting your data accidentally.

**Drift detection:** Run `terraform plan` on a schedule (nightly) even when
you're not making changes. If the plan shows a diff, someone changed
infrastructure outside Terraform — a security or compliance red flag.

---

## 10. Exercise

After running `terraform apply` for the dev environment:

1. **Verify the outputs** match what your `deploy-cloud-run.ps1` script was using:
   ```powershell
   cd terraform
   terraform output -var-file="environments/dev/terraform.tfvars"
   ```

2. **Increase ChromaDB memory** from `512Mi` to `1Gi` in `modules/cloud_run/main.tf`,
   run `terraform plan`, and observe that only the ChromaDB service shows a change
   — not the backend service.

3. **Import the existing Artifact Registry repo** into Terraform state so
   it's no longer managed manually:
   ```powershell
   terraform import module.artifact_registry.google_artifact_registry_repository.docker_repo \
     projects/android-ai-assistant-89cec/locations/asia-south1/repositories/backend
   ```

4. **Add a fourth IAM role** to `modules/iam/main.tf` — `roles/cloudscheduler.jobRunner` —
   and observe how `for_each` adds only that one binding without touching the others.

---

## Phase 7 Summary

You now have:

```
terraform/
├── Root config (providers, backend, variables, main, outputs)
├── modules/iam            → service account + Workload Identity Federation
├── modules/artifact_registry → Docker registry
├── modules/storage        → GCS bucket + HMAC keys → Secret Manager
├── modules/cloud_run      → FastAPI + ChromaDB on Cloud Run
└── environments/          → dev and prod variable files
```

**What this replaces:**
- `scripts/setup-iam.ps1` → `modules/iam`
- `scripts/setup-gcs.ps1` → `modules/storage`
- `scripts/setup-wif.ps1` → `modules/iam` (WIF section)
- `scripts/deploy-cloud-run.ps1` → `modules/cloud_run`

The manual scripts are still useful for one-off operations and debugging.
Terraform is the source of truth for the desired state.

**Next phase:** Phase 8 — Observability (Structured Logs, Metrics, Traces).
Say `NEXT` to continue.
