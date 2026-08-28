# ─────────────────────────────────────────────────────────────────────────────
# environments/prod/terraform.tfvars
#
# Variable values for the PROD environment.
#
# HOW TO USE:
#   cd terraform
#   terraform init -backend-config="prefix=terraform/state/prod"
#   terraform plan  -var-file="environments/prod/terraform.tfvars"
#   terraform apply -var-file="environments/prod/terraform.tfvars"
#
# PRODUCTION DIFFERENCES vs dev:
#   - backend_max_instances = 2  (handles real traffic bursts)
#   - backend_memory = 1Gi       (full memory for production workloads)
#   - llm_max_tokens higher      (better responses for real users)
#   - concurrency = 40           (handles more parallel users per instance)
# ─────────────────────────────────────────────────────────────────────────────

# ── Project ───────────────────────────────────────────────────────────────────
project_id     = "android-ai-assistant-89cec"
project_number = "106071012091"
region         = "asia-south1"
environment    = "prod"

# ── Service account ───────────────────────────────────────────────────────────
backend_sa_name = "ai-assistant-backend"

# ── Storage ───────────────────────────────────────────────────────────────────
storage_bucket_name = ""
storage_location    = "asia-south1"

# ── Artifact Registry ─────────────────────────────────────────────────────────
artifact_repo_name = "backend"

# ── Backend image ─────────────────────────────────────────────────────────────
# In production, backend_image_tag is always overridden by CI with the git SHA.
# The value here is a safe fallback — CI never uses "latest" in prod.
backend_image     = "asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api"
backend_image_tag = "latest"

# ── Cloud Run — backend scaling ───────────────────────────────────────────────
backend_min_instances = 0    # still scale to zero — cost discipline
backend_max_instances = 2    # allow 2 instances under load
backend_cpu           = "1"
backend_memory        = "1Gi"
backend_concurrency   = 40

# ── Cloud Run — ChromaDB scaling ──────────────────────────────────────────────
chroma_image         = "chromadb/chroma:1.5.9"
chroma_min_instances = 0
chroma_max_instances = 1

# ── LLM config ────────────────────────────────────────────────────────────────
default_llm_provider  = "gemini"
llm_fallback_provider = "openai"

# Full token limits in prod for quality responses
llm_max_tokens_openai = 2048
llm_max_tokens_gemini = 4096
llm_max_tokens_claude = 2048

# ── OAuth client IDs ──────────────────────────────────────────────────────────
google_client_id         = "106071012091-d4brm5cng1gaor0al51veafjd0fa239v.apps.googleusercontent.com"
google_android_client_id = "106071012091-0cu6q2e6b4tsa1qrauutqt70ms13otn6.apps.googleusercontent.com"

# ── Workload Identity Federation ──────────────────────────────────────────────
github_org  = "kotlinandfluttertutorial-lab"
github_repo = "Android-AI-Assistant-Test"
