# Manual Steps Remaining

The following tasks must be completed manually outside the repository.

| # | What | Where |
|---|------|-------|
| 1 | Rotate any API keys that were in the committed `.env` (especially Gemini key) | Your key management |
| 2 | Fill in production secrets on staging + production servers | Server `.env` files |
| 3 | Configure GitHub Actions secrets (GCP WIF, keystore, Firebase, etc.) | GitHub → Settings → Secrets |
| 4 | Set GitHub Environment protection rules (staging + production) | GitHub → Settings → Environments |
| 5 | Set branch protection rules for `main` | GitHub → Settings → Branches |
| 6 | Set `google_web_client_id` in `strings.xml` to your real Web Client ID | Already has placeholder |
| 7 | Set `CERTIFICATE_PINS` Gradle property before release builds | `./gradlew assembleRelease -Pcert_pins=...` |
| 8 | Run the Cloud Run deploy checklist (Task 16) | `CLOUD_RUN_DEPLOYMENT.md` |
| 9 | Firebase Remote Config setup (Task 14) | Firebase Console |
| 10 | MCP connector tokens when integrations are needed (Task 15) | `.env` on server |
