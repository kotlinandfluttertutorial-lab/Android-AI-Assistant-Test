# Developer Todo List — Android AI Assistant (Enterprise Edition)

> **Last updated:** August 1, 2026
> **Purpose:** Reference checklist for taking the project from fully implemented spec to a production-ready, deployable system.
> **Status legend:** 🔴 Critical · 🟠 High Priority · 🟡 Medium Priority · 🟢 Lower Priority

---

## Project State Summary

| Layer | Status |
|-------|--------|
| Backend (FastAPI services) | ✅ Fully implemented |
| Backend tests (40+ files) | ✅ Real pytest/Hypothesis suite |
| Alembic migrations (6 files) | ✅ Complete schema |
| Docker / docker-compose | ✅ Production-ready |
| Nginx, Prometheus, Loki configs | ✅ Real configs |
| Android domain layer | ✅ Fully implemented |
| Android data layer | ✅ Fully implemented |
| core-security (SecureStorage, Biometric) | ✅ Real implementations |
| feature-auth, feature-chat, feature-voice | ✅ Real screens + ViewModels |
| 12 remaining feature modules | ⚠️ UI layer needs verification |
| Android unit tests | ⚠️ Lagging behind backend |
| CI/CD secrets & environments | ⚠️ Not configured |
| Production env vars | 🔴 Placeholders only |

---

## 🔴 Critical — Do Before Anything Else ✅

### 1. Remove `backend/.env` from the repository ✅

The `.env` file was committed to Git. It has been removed from tracking.

```bash
git rm --cached backend/.env
```

- [x] Run the commands above
- [ ] Rotate **every** key that was ever in that file (especially the Gemini API key visible in `.env.example`)
- [x] Confirm `git log --all --full-history -- backend/.env` shows no future commits include the file
- [x] Verify `.gitignore` covers both `backend/.env` and root `.env`

---

### 2. Generate real production secrets

Copy `backend/.env.example` → `backend/.env` (on the server only, never commit it) and fill in:

| Variable | How to generate / where to get it |
|----------|------------------------------------|
| `SECRET_KEY` | `python3 -c "import secrets; print(secrets.token_hex(32))"` |
| `AES_ENCRYPTION_KEY` | `python3 -c "import secrets; print(secrets.token_hex(32))"` — required for LLM API key encryption at rest |
| `OPENAI_API_KEY` | platform.openai.com → API Keys |
| `ANTHROPIC_API_KEY` | console.anthropic.com → API Keys |
| `GEMINI_API_KEY` | console.cloud.google.com → Credentials |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google Cloud Console → OAuth 2.0 → Web application — **Google OAuth is broken without these** |
| `FIREBASE_CREDENTIALS_PATH` | Firebase Console → Project Settings → Service Accounts → Generate private key — **push notifications are broken without this** |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | Set any strong random value on first deploy |
| `SMTP_HOST` / `SMTP_USER` / `SMTP_PASSWORD` | Your email provider (SendGrid, Postmark, etc.) — **account lockout emails are silently dropped without these** |

- [ ] All variables above filled in on staging server
- [ ] All variables above filled in on production server
- [ ] `LLM_FALLBACK_PROVIDER` added to `.env.example` — the orchestrator reads this setting but it is not yet documented

---

### 3. Fix ChromaDB port conflict ✅

`CHROMA_PORT` defaults to `8000` in `docker-compose.yml` — the same port as the FastAPI backend. This causes a silent startup conflict.

- [x] Change ChromaDB port to `8001` in `docker-compose.yml`
- [x] Update `backend/.env.example` → `CHROMA_HOST=chromadb` / `CHROMA_PORT=8001`
- [x] Update `backend/app/config/settings.py` default

---

## 🟠 High Priority — Required for the App to Compile and Run

### 4. Add `@Inject` constructor annotation to domain use cases ✅

`LoginUseCase` (and likely other use cases) had this TODO comment:
> *"Add @Inject constructor annotation once javax.inject is added to domain/build.gradle.kts."*

Hilt cannot inject any use case without this.

- [x] `domain/build.gradle.kts` updated with `javax.inject`
- [x] All use cases under `domain/src/main/kotlin/.../usecase/` annotated
- [x] App builds and Hilt component graph generates without errors

---

### 5. Add missing string resources for BiometricAuthManager ✅

`BiometricAuthManager` calls `R.string.biometric_prompt_title`, `R.string.biometric_prompt_subtitle`, and `R.string.biometric_prompt_negative_button`.

- [x] `core-security/src/main/res/values/strings.xml` created/verified
- [x] Biometric flow tested on a device or emulator with fingerprint enrolled

---

### 6. Verify and complete the 12 remaining Android feature module UIs

`feature-auth`, `feature-chat`, and `feature-voice` are confirmed complete. Each module below needs a verification pass — the `data/` repository exists but the UI layer (screens, ViewModel, navigation) may be sparse or have a `.gitkeep` placeholder.

For each module, verify these files exist and contain real code (not just an empty class):

| Module | Required files to verify |
|--------|--------------------------|
| `feature-rag` | `DocumentListScreen`, `DocumentChatScreen`, `RAGViewModel`, upload bottom sheet, status polling |
| `feature-camera` | `CameraCapture`, `ImageAnalysisScreen`, `OCRResultScreen`, `CameraViewModel`, QR/barcode decode |
| `feature-code` | `CodeEditorScreen`, `CodeAnalysisScreen`, `CodeViewModel`, syntax highlighting composable |
| `feature-notes` | `NotesListScreen`, `NoteEditorScreen`, `NotesViewModel`, Markdown live preview |
| `feature-meeting` | `MeetingRecorderScreen`, `MeetingSummaryScreen`, `MeetingViewModel`, MediaRecorder state machine |
| `feature-translator` | `TranslatorScreen`, `TranslatorViewModel`, online/offline routing, language pair selector |
| `feature-productivity` | `TodoListScreen`, `TodoEditorScreen`, `CalendarViewScreen`, `ReminderEditorScreen`, `HabitListScreen`, `HabitInsightsScreen`, `ProductivityViewModel` |
| `feature-profile` | `ProfileScreen`, `MemoryListScreen`, `ProfileViewModel` |
| `feature-history` | `HistoryListScreen`, `SearchHistoryScreen`, `HistoryViewModel` |
| `feature-settings` | `SettingsScreen`, provider selector, theme selector, `SettingsViewModel` |
| `feature-resume` | `ResumeBuilderScreen`, `CoverLetterEditorScreen`, `ResumeViewModel` |
| `feature-email` | `EmailComposerScreen`, `GrammarCorrectionScreen`, `EmailViewModel`, inline diff view |

- [ ] `feature-rag` verified / completed
- [ ] `feature-camera` verified / completed
- [ ] `feature-code` verified / completed
- [ ] `feature-notes` verified / completed
- [ ] `feature-meeting` verified / completed
- [ ] `feature-translator` verified / completed
- [ ] `feature-productivity` verified / completed
- [ ] `feature-profile` verified / completed
- [ ] `feature-history` verified / completed
- [ ] `feature-settings` verified / completed
- [ ] `feature-resume` verified / completed
- [ ] `feature-email` verified / completed

---

## 🟡 Medium Priority — Required Before First User-Facing Build

### 7. Write Android unit and ViewModel tests

The backend has 40+ test files. The Android side lags significantly. Work in this order (fastest ROI first):

**Phase 1 — Pure JVM tests (no emulator, no Robolectric):**
- [ ] `domain/src/test/` — unit tests for every use case (mock repositories with MockK or Mockito)
- [ ] Focus on: `LoginUseCase`, `RegisterUseCase`, `SendMessageUseCase`, `GetHabitInsightsUseCase` (7-day gate), `SummarizeNoteUseCase` (150-word limit)

**Phase 2 — ViewModel tests (JUnit + Turbine):**
- [ ] `feature-auth/src/test/` — `AuthViewModel`: login/register/biometric/error flows
- [ ] `feature-chat/src/test/` — `ChatViewModel`: date grouping, pagination, offline banner
- [ ] `feature-voice/src/test/` — `VoiceViewModel`: full state machine cycle

**Phase 3 — Compose UI tests (instrumented):**
- [ ] `core-ui/src/androidTest/` — `MarkdownText`, `CodeBlock`, `ChatBubble`, adaptive layout breakpoints
- [ ] `feature-auth/src/androidTest/` — login validation errors, biometric prompt, privacy policy display

**Minimum bar:** JaCoCo combined coverage on `domain` + `data` modules ≥ 70% (enforced by CI).

---

### 8. Configure GitHub Actions secrets

The CI/CD pipelines in `.github/workflows/` are ready but will fail until these secrets are set.

Go to: **GitHub → Repository → Settings → Secrets and variables → Actions → New repository secret**

> **Firebase App Distribution — one-time setup (kotlinfiroj@gmail.com)**
> 1. Go to [console.firebase.google.com](https://console.firebase.google.com) → sign in with **kotlinfiroj@gmail.com**
> 2. Select your project → **Project Settings → Your apps** → copy the **App ID** (`1:xxxx:android:xxxx`)
> 3. **IAM & Admin → Service Accounts** → create a service account → grant role **Firebase App Distribution Admin**
> 4. Generate a JSON key → `base64 -w 0 service-account.json` → save as `FIREBASE_SERVICE_ACCOUNT` secret
> 5. **App Distribution → Testers & Groups** → create at least one group (e.g. `qa-team`) → save alias as `FIREBASE_TESTER_GROUPS` secret

| Secret name | Value |
|-------------|-------|
| `KEYSTORE_BASE64` | `base64 -w 0 your-release-keystore.jks` |
| `KEY_ALIAS` | Key alias inside the keystore |
| `KEY_PASSWORD` | Password for that key |
| `KEYSTORE_PASSWORD` | Password for the keystore file |
| `GOOGLE_SERVICES_JSON` | `base64 -w 0 app/google-services.json` |
| `FIREBASE_APP_ID` | Firebase Console → Project Settings → Your apps → App ID (format: `1:xxxx:android:xxxx`) |
| `FIREBASE_SERVICE_ACCOUNT` | Firebase IAM → Service account JSON with `roles/firebaseappdistro.admin` (base64 or raw) |
| `FIREBASE_TESTER_GROUPS` | Comma-separated tester group aliases e.g. `qa-team,beta-testers` |
| `STAGING_SSH_HOST` | IP or hostname of staging server |
| `STAGING_SSH_USER` | SSH username on staging server |
| `STAGING_SSH_KEY` | Private SSH key (PEM format) for staging |
| `PROD_SSH_HOST` | IP or hostname of production server |
| `PROD_SSH_USER` | SSH username on production server |
| `PROD_SSH_KEY` | Private SSH key (PEM format) for production |
| `NVD_API_KEY` | Get free key at https://nvd.nist.gov/developers/request-an-api-key |
| `SLACK_WEBHOOK_URL` | Slack → Apps → Incoming Webhooks (optional) |

- [ ] All secrets above configured in GitHub

---

### 9. Configure GitHub repository variables

Go to: **GitHub → Repository → Settings → Secrets and variables → Actions → Variables tab**

| Variable name | Value |
|---------------|-------|
| `STAGING_COMPOSE_PATH` | Absolute path to project on staging server e.g. `/opt/ai-assistant` |
| `PROD_COMPOSE_PATH` | Absolute path to project on production server e.g. `/opt/ai-assistant` |
| `SLACK_WEBHOOK_URL` | Same as secret above if using variable instead |

- [ ] Variables configured

---

### 10. Set up GitHub Environment protection rules

Go to: **GitHub → Repository → Settings → Environments**

Create two environments and configure them:

**`staging`**
- [ ] Set deployment branch to `main` only
- [ ] Add URL: your staging domain

**`production`**
- [ ] Add at least 1 required reviewer (yourself or a team lead)
- [ ] Set deployment branch to `main` only
- [ ] Add wait timer: 5 minutes (gives time to cancel accidental deploys)
- [ ] Add URL: your production API domain

---

### 11. Configure branch protection rules for `main`

Go to: **GitHub → Repository → Settings → Branches → Add rule → Branch name: `main`**

- [ ] Require a pull request before merging
- [ ] Require at least 1 approving review
- [ ] Require status checks to pass — add all of these:
  - `android / lint`
  - `android / unit-tests`
  - `android / detekt`
  - `android / ktlint`
  - `android / jacoco-coverage`
  - `android / instrumented-tests`
  - `backend / lint-and-type-check`
  - `backend / unit-and-property-tests`
  - `backend / integration-tests`
- [ ] Require branches to be up to date before merging
- [ ] Do not allow bypassing the above settings (uncheck "Allow administrators to bypass")

---

## 🟢 Lower Priority — Before Production Launch

### 12. Replace placeholder URLs in CI/CD workflows

The release and deploy pipelines contain example domain names. Update them to your real domains.

**Files to edit:**

`.github/workflows/backend-ci.yml` — replace:
```
https://staging.aiassistant.example.com  →  your actual staging domain
https://api.handsonandroid.com      →  your actual production API domain
```

`.github/workflows/release.yml` — replace the same two URLs plus:
```
com.aiassistant.app  →  your actual Android application ID
```

- [ ] `backend-ci.yml` URLs updated
- [ ] `release.yml` URLs and package name updated
- [ ] `infrastructure/nginx/nginx.conf` `server_name` directive updated

---

### 13. Update CODEOWNERS with real team members

`.github/CODEOWNERS` currently uses placeholder team names.

Replace every instance of `@your-org/` with your actual GitHub organisation and team slugs, or individual GitHub usernames for a smaller team.

- [ ] `@your-org/android-ai-team` → real owner
- [ ] `@your-org/devops-team` → real owner
- [ ] `@your-org/security-team` → real owner
- [ ] `@your-org/backend-team` → real owner
- [ ] `@your-org/android-core-team` → real owner
- [ ] `@your-org/android-feature-team` → real owner

---

### 14. Wire Firebase Remote Config

Firebase Remote Config lets you change runtime flags (e.g. feature flags, LLM provider defaults) without an app update.

- [ ] Create a Firebase project at console.firebase.google.com if not already done
- [ ] Download `google-services.json` and place at `app/google-services.json`
- [ ] Download service account JSON and reference it via `FIREBASE_CREDENTIALS_PATH` on the server
- [ ] Define default Remote Config parameters in the Firebase Console that match what the app reads

---

### 15. Configure MCP tool connectors

All 8 MCP connector tokens are blank. These are optional for launch but required for the MCP integrations to function.

| MCP Tool | Required credentials |
|----------|---------------------|
| GitHub | `GITHUB_TOKEN` — Personal Access Token or GitHub App |
| Gmail | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` (OAuth) |
| Google Drive | Same OAuth credentials as Gmail |
| Google Calendar | Same OAuth credentials as Gmail |
| Slack | `SLACK_BOT_TOKEN` — from api.slack.com/apps |
| Jira | `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN` |
| Notion | `NOTION_TOKEN` — from notion.so/my-integrations |
| Figma | `FIGMA_ACCESS_TOKEN` — from figma.com/settings |

- [ ] Add each token to `backend/.env` on the server (not the repo)
- [ ] Test each connector via `POST /api/v1/tools/invoke` with a test payload
- [ ] Document any OAuth redirect URIs that need to be whitelisted

---

### 16. First deploy checklist (staging)

Run through this sequence on staging before touching production:

- [ ] SSH into staging server, clone/pull latest `main`
- [ ] Create `backend/.env` from `.env.example` with real values
- [ ] Create root `.env` from `.env.example` with real values
- [ ] `docker compose pull` — pull all base images
- [ ] `docker compose up -d postgres redis minio chromadb` — start infrastructure
- [ ] `docker compose run --rm backend alembic upgrade head` — run migrations
- [ ] `docker compose up -d backend celery_worker nginx prometheus loki grafana` — start everything
- [ ] `curl https://staging.yourdomain.com/health` returns `{"status":"healthy"}`
- [ ] `curl https://staging.yourdomain.com/ready` returns `{"status":"ready"}`
- [ ] Run the Android app against the staging backend (update `BASE_URL` in local.properties)
- [ ] Complete a full auth flow (register → login → biometric unlock)
- [ ] Send a test chat message and verify streaming response

---

### 17. First deploy checklist (production)

Only after staging is stable:

- [ ] Tag the release: `git tag v1.0.0 && git push origin v1.0.0`
- [ ] The `release.yml` workflow triggers automatically
- [ ] Monitor the GitHub Actions run — approve the production environment gate when prompted
- [ ] Verify `/health` and `/ready` on production
- [ ] Verify Play Store upload reached the Internal Testing track
- [ ] Install from Play Store internal track on a real device
- [ ] Run a full end-to-end test: auth → chat → voice → document upload → productivity

---

---

## Recommended Execution Order

Work through the priorities in this sequence for the fastest path to a production-ready system:

```
Week 1 — Unblock the basics
  Day 1:  Task 1  (remove .env from git — do this NOW)
          Task 3  (fix ChromaDB port conflict)
  Day 2:  Task 4  (@Inject on use cases — app needs to build clean)
          Task 5  (biometric string resources)
  Day 3+: Task 6  (verify/complete each feature module UI)

Week 2 — Test coverage
  Task 7 Phase 1  (domain use case unit tests)
  Task 7 Phase 2  (ViewModel tests)

Week 3 — CI/CD and infrastructure
  Task 2   (fill in all real secrets on servers)
  Task 8   (GitHub Actions secrets)
  Task 9   (GitHub repository variables)
  Task 10  (Environment protection rules)
  Task 11  (Branch protection rules)

Week 4 — Staging deploy and smoke test
  Task 16  (full staging deploy checklist)
  Task 12  (fix placeholder URLs now that you have real domains)
  Task 13  (CODEOWNERS)

Week 5 — Soft launch prep
  Task 7 Phase 3  (Compose UI tests)
  Task 14  (Firebase Remote Config)
  Task 17  (production deploy checklist)

Ongoing (post-launch)
  Task 15  (MCP connectors — as each integration is needed)
```

---

## Quick Reference — Key File Locations

| What | Where |
|------|-------|
| Spec tasks | `.kiro/specs/android-ai-assistant/tasks.md` |
| Requirements | `.kiro/specs/android-ai-assistant/requirements.md` |
| Architecture design | `.kiro/specs/android-ai-assistant/design.md` |
| API specification | `docs/api-specification.md` |
| Security guide | `docs/security-guide.md` |
| Deployment guide | `docs/deployment-guide.md` |
| CI/CD workflows | `.github/workflows/` |
| Backend env template | `backend/.env.example` |
| Root env template | `.env.example` |
| Alembic migrations | `backend/alembic/versions/` |
| Docker Compose | `docker-compose.yml` |
| Nginx config | `infrastructure/nginx/nginx.conf` |
| Prometheus config | `infrastructure/prometheus/prometheus.yml` |
| Grafana dashboards | `infrastructure/grafana/provisioning/dashboards/` |

---

## Completion Tracker

Update the checkboxes in each section as you work through the list. When all items in a section are checked, mark the section header with ✅.

| Section | Items | Done |
|---------|-------|------|
| 🔴 Critical | 3 tasks, ~8 items | ☐ |
| 🟠 High Priority | 3 tasks, ~20 items | ☐ |
| 🟡 Medium Priority | 5 tasks, ~30 items | ☐ |
| 🟢 Lower Priority | 6 tasks, ~25 items | ☐ |
| **Total** | **17 tasks** | **☐** |

---

*Document maintained by the development team. Update the "Last updated" date at the top whenever this file is revised.*
