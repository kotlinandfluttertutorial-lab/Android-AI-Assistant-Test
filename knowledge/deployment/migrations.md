# Database Migrations — Alembic

**Last updated:** 2026-08-26
**Tool:** Alembic (SQLAlchemy migration framework)
**Database:** Neon PostgreSQL (serverless)

---

## Overview

Alembic manages the PostgreSQL schema. Migrations are versioned files in
`backend/alembic/versions/` and run automatically as part of every deployment
via a Cloud Run Job.

Current migrations (6 files):
1. `001_initial_schema.py` — users, sessions, refresh_tokens
2. `002_documents.py` — documents, document_chunks
3. `003_conversations.py` — conversations, messages
4. `004_jobs.py` — jobs (Celery task tracking)
5. `005_productivity.py` — todos, habits, reminders
6. `006_memory.py` — user memory entries

---

## Running migrations in production

Migrations run as a Cloud Run Job (exits when done, billed only for execution):

```bash
PROJECT=android-ai-assistant-89cec
REGION=asia-south1
IMAGE=asia-south1-docker.pkg.dev/$PROJECT/backend/api:latest

# Create the job (one-time, or when image changes)
gcloud run jobs create alembic-migrate \
  --image=$IMAGE \
  --region=$REGION \
  --service-account=ai-assistant-backend@$PROJECT.iam.gserviceaccount.com \
  --set-secrets="DATABASE_URL=DATABASE_URL:latest,SECRET_KEY=SECRET_KEY:latest,REDIS_URL=REDIS_URL:latest" \
  --set-env-vars="ENVIRONMENT=production" \
  --command="python" \
  --args="-m,alembic,upgrade,head" \
  --max-retries=1

# Execute (run after each deploy that includes schema changes)
gcloud run jobs execute alembic-migrate --region=$REGION --wait
```

The CI/CD workflow (`cloud-run-deploy.yml`) runs migrations automatically before
deploying the new revision.

---

## Running migrations locally

```bash
# From backend/ directory
cd backend

# Ensure DATABASE_URL is set in backend/.env
alembic current       # show current migration head
alembic history       # list all migrations
alembic upgrade head  # apply all pending migrations
alembic downgrade -1  # rollback one migration (use with caution)
```

---

## Creating a new migration

```bash
cd backend

# Auto-generate from SQLAlchemy model changes
alembic revision --autogenerate -m "add_user_preferences_table"

# Edit the generated file in alembic/versions/
# ALWAYS review auto-generated migrations before committing

# Test it locally
alembic upgrade head

# Verify the schema looks correct
psql $DATABASE_URL -c "\d user_preferences"
```

**Rules for migrations:**
- Never modify an existing migration file after it has been applied to any environment
- Always test `upgrade` AND `downgrade` locally before committing
- Include a docstring explaining what the migration does and why
- Avoid destructive operations (DROP COLUMN, DROP TABLE) in the same migration as
  the code change — use a two-phase deploy

---

## Two-phase deploy for destructive changes

Removing a column that existing code still reads will break the running service.
Use this pattern:

**Phase 1 deploy:** Remove the column from the application code (stop reading it)
**Phase 2 deploy:** Add the migration that drops the column

This ensures no revision is deployed that tries to read a column that doesn't exist.

---

## Checking migration status

```bash
# Check what the production database is currently at
gcloud run jobs create alembic-current \
  --image=$IMAGE \
  --region=$REGION \
  --service-account=ai-assistant-backend@$PROJECT.iam.gserviceaccount.com \
  --set-secrets="DATABASE_URL=DATABASE_URL:latest,SECRET_KEY=SECRET_KEY:latest,REDIS_URL=REDIS_URL:latest" \
  --set-env-vars="ENVIRONMENT=production" \
  --command="python" \
  --args="-m,alembic,current" \
  --max-retries=0

gcloud run jobs execute alembic-current --region=$REGION --wait
gcloud run jobs executions list --job=alembic-current --region=$REGION --limit=1
```

---

## Emergency rollback

To revert the last migration:

```bash
# Run downgrade in a Cloud Run job
gcloud run jobs create alembic-downgrade \
  --image=$IMAGE \
  --region=$REGION \
  --service-account=ai-assistant-backend@$PROJECT.iam.gserviceaccount.com \
  --set-secrets="DATABASE_URL=DATABASE_URL:latest,SECRET_KEY=SECRET_KEY:latest,REDIS_URL=REDIS_URL:latest" \
  --set-env-vars="ENVIRONMENT=production" \
  --command="python" \
  --args="-m,alembic,downgrade,-1" \
  --max-retries=0

gcloud run jobs execute alembic-downgrade --region=$REGION --wait
```

Then roll back the Cloud Run service to the previous revision (see `rollback.md`).

---

## Neon free tier constraints

- Max connections: 100 (shared across all app instances)
- Storage: 512 MB (free tier)
- Branches: 10 (useful for testing migrations on a database branch before production)

To create a Neon branch for testing a migration:
1. Go to Neon console → Branches → New Branch
2. Get the connection string for the new branch
3. Run `alembic upgrade head` against the branch URL
4. Verify the migration looks correct
5. Delete the branch and apply to production
