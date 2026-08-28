# ─────────────────────────────────────────────────────────────────────────────
# backend.tf
#
# Configures WHERE Terraform stores its state file.
#
# WHY REMOTE STATE?
#   By default Terraform writes state to terraform.tfstate on your local disk.
#   That breaks team collaboration — two people running terraform apply
#   simultaneously will corrupt state. Storing state in GCS gives you:
#     - A single source of truth accessible from any machine or CI runner
#     - State locking (GCS provides this natively) — prevents concurrent runs
#     - Versioning — you can see exactly what state looked like before a change
#
# BEFORE RUNNING terraform init:
#   Create the state bucket once (this is the ONLY resource you provision
#   manually — everything else is managed by Terraform itself):
#
#     $PROJECT = "android-ai-assistant-89cec"
#     $REGION  = "asia-south1"
#     gsutil mb -l $REGION gs://$PROJECT-tfstate
#     gsutil versioning set on gs://$PROJECT-tfstate
#
# The bucket name below must match what you created above.
# ─────────────────────────────────────────────────────────────────────────────

terraform {
  backend "gcs" {
    bucket = "android-ai-assistant-89cec-tfstate"
    prefix = "terraform/state"
    # Each environment uses a different prefix so state is isolated:
    #   dev  → terraform/state/dev/default.tfstate
    #   prod → terraform/state/prod/default.tfstate
    # Set via: terraform init -backend-config="prefix=terraform/state/dev"
  }
}
