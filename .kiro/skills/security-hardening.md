# Skill: Security Hardening

## Purpose
Apply, verify, and extend the security controls that are already built into the Android
AI Assistant. This skill covers both the Android client and the FastAPI backend, mapping
each control to the relevant code location.

## When to Use
- Implementing a new endpoint or feature that touches credentials, user data, or AI I/O
- Rotating TLS certificate pins
- Adding a new secret to the backend
- Reviewing a PR for security regressions
- Responding to a finding from the `security-scan.yml` CI job

---

## Android Security Controls

### 1. Encrypted Storage — `SecureStorageImpl`

**Location:** `core-security/src/main/kotlin/com/aiassistant/core/security/SecureStorageImpl.kt`

All credentials (JWT, refresh token, FCM token) are stored in
`EncryptedSharedPreferences` backed by a `MasterKey` (AES-256-GCM).

Rules:
- Never store a credential in plain `SharedPreferences` or `DataStore`.
- Never log a token — log only its presence/absence.
- Call `secureStorage.clearAll()` on every logout path.
- FCM token lifecycle: `saveFcmToken()` → upload → `saveFcmTokenSynced()`.

```kotlin
// CORRECT — read via interface, never cast to Impl
class SomeViewModel @Inject constructor(private val storage: SecureStorage) {
    fun getJwt(): String? = storage.getJwt()  // returns null if not logged in
}

// WRONG
val prefs = context.getSharedPreferences("tokens", Context.MODE_PRIVATE)
prefs.edit().putString("jwt", token).apply()  // plaintext!
```

### 2. Certificate Pinning — `CertificatePinningInterceptor`

**Location:** `core-network/src/main/kotlin/com/aiassistant/core/network/CertificatePinningInterceptor.kt`

SHA-256 SPKI hashes are injected via `NetworkModule` from `BuildConfig`. The
`bypass = true` flag is only set in `debug` build type.

**Pin rotation procedure:**
1. Obtain the new certificate's SPKI SHA-256:
   ```bash
   openssl s_client -connect ai-assistant-backend-106071012091.asia-south1.run.app:443 \
       </dev/null 2>/dev/null | openssl x509 -pubkey -noout \
       | openssl pkey -pubin -outform DER \
       | openssl dgst -sha256 -binary | base64
   ```
2. Update `BuildConfig` field (or string resource) in `NetworkModule`.
3. Keep **both** old and new pins active during the transition window (at least one
   full app-version release cycle).
4. The CI check `check-tls-pin.sh` verifies that the pin in source matches the live
   server cert — run it before and after rotation.

```kotlin
// NetworkModule.kt — production binding
@Provides
@Singleton
fun provideCertPinningInterceptor(): CertificatePinningInterceptor =
    CertificatePinningInterceptor(
        pinnedSha256Hashes = setOf(BuildConfig.TLS_PIN_PRIMARY, BuildConfig.TLS_PIN_BACKUP),
        bypass = BuildConfig.DEBUG,
    )
```

### 3. Biometric Authentication — `BiometricAuthManager`

**Location:** `core-security/src/main/kotlin/com/aiassistant/core/security/BiometricAuthManager.kt`

Used to gate access to the app after a configurable idle timeout. Biometric data never
leaves the device — the OS returns only a `BiometricPrompt.AuthenticationResult`.

Rules:
- Never pass biometric callbacks on `Dispatchers.IO`; they run on the main thread.
- Always call `authenticate()` before revealing sensitive data (chat history, profile).

### 4. Root Detection — `RootDetectionUtil`

**Location:** `core-security/src/main/kotlin/com/aiassistant/core/security/RootDetectionUtil.kt`

Check on app startup. Log a warning, optionally block feature access. Do not
silently continue on a rooted device when processing financial or health data.

### 5. Screenshot Protection

For screens showing credentials or conversation history:

```kotlin
@Composable
fun SecureScreen(content: @Composable () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    content()
}
```

Apply to: `ChatDetailScreen`, `ProfileScreen`, `SettingsScreen` (API key section).

### 6. ProGuard / R8

Release build has `isMinifyEnabled = true` and `isShrinkResources = true`.
`proguard-rules.pro` already keeps:
- Kotlin metadata (`kotlinx.**`)
- kotlinx.serialization (`@Serializable` classes)
- Retrofit interfaces, OkHttp, Hilt, Room, Firebase, Crashlytics
- All classes under `com.aiassistant.domain.**` and `com.aiassistant.data.**`

If you add a new library that uses reflection, add a `-keep` rule. Never remove
existing `-keep` rules without verifying the release build works end-to-end.

---

## Backend Security Controls

### 7. Prompt Injection Detection — `AIOrchestrator`

**Location:** `backend/app/services/ai_orchestrator.py` → `_detect_prompt_injection_static()`

13+ regex patterns block injection attempts before any LLM call. When adding a new
pattern:
```python
_INJECTION_PATTERNS.append(
    re.compile(r"your_new_pattern", re.IGNORECASE)
)
```
Add the pattern to `tests/unit/test_ai_orchestrator.py` with a corresponding test case.

### 8. Safety Filters — `SafetyService`

**Location:** `backend/app/services/safety_service.py`

`filter_response()` is called on **every token** before it reaches the WebSocket. If
`SafetyFilterError` is raised, the entire response is blocked (not just the bad token).
Do not buffer tokens client-side before safety filtering — the filter runs server-side.

### 9. JWT & Authentication

All non-public endpoints require `Depends(get_current_user)`.

Tokens are:
- Signed with `SECRET_KEY` (HS256, required at startup via `REQUIRED_ENV_VARS`)
- Short-lived access tokens + longer-lived refresh tokens
- Stored on the Android client in `SecureStorageImpl` only

If adding a new public endpoint (no auth required), justify it explicitly in the PR
and add it to the `CORS_ALLOW_LIST`.

### 10. Secret Management

**Local dev:** `.env` file (never committed — `.gitignore` excludes it; Gitleaks CI scans history)

**Production (Cloud Run):** GCP Secret Manager. Every secret is mounted at deploy time
in `cloud-run-deploy.yml`. `AES_ENCRYPTION_KEY` is mandatory — the app exits with
code 1 if missing (enforced by `startup_validation()`).

When adding a new secret:
1. Add to `.env.example` with a placeholder value and a comment.
2. Add to `REQUIRED_ENV_VARS` in `backend/app/main.py` if it is mandatory at startup.
3. Create the secret in GCP Secret Manager.
4. Add a `--set-secrets` flag in `cloud-run-deploy.yml`.
5. Add it to `.gitleaks.toml` if it has a recognisable format (API key prefix, etc.).

### 11. Rate Limiting

`RateLimitMiddleware` uses Redis. Default limits are set in `app/config/settings.py`.
Do not bypass rate limiting in an endpoint. If a route needs higher limits (e.g. a
WebSocket streaming endpoint), adjust the config — do not skip the middleware.

### 12. Audit Logging — MCP Tool Calls

Every `MCPBroker.invoke()` call writes exactly one `AuditLog` entry. This is a
**hard requirement** (Property 12). Do not add MCP tool invocations that bypass
`MCPBroker`. If you add a new connector, verify the audit log entry is written in your
integration test.

### 13. Persona Safety Guardrails

The `AIOrchestrator.build_system_prompt()` appends platform safety rules to
**all** personas for non-admin users (Req 32.4). Do not allow admin-only persona
configurations to leak to regular users.

---

## CI Security Checks (`.github/workflows/security-scan.yml`)

| Job | Tool | Fails on |
|---|---|---|
| `codeql` | CodeQL | security-extended + quality queries |
| `secret-scan` | Gitleaks | any secret pattern in git history |
| `bandit` | Bandit | HIGH severity + HIGH confidence |
| `trivy-image` | Trivy | CRITICAL CVEs |
| `trivy-fs` | Trivy | SARIF upload, non-blocking |
| `pip-audit` | pip-audit | OSV + PyPI advisories |
| `owasp-dep-check` | OWASP DC | scheduled only |
| `tls-pin-check` | `check-tls-pin.sh` | pin mismatch |

Addressing a finding:
1. Fix the root cause (preferred).
2. If a false positive, add a suppression to `.trivyignore`, `dependency-check-suppression.xml`,
   or `.gitleaks.toml` with a comment explaining why it is safe to suppress.
3. Never suppress a CRITICAL finding without a documented justification and a linked issue.

---

## Checklist

- [ ] Credentials stored only via `SecureStorage` (never plain `SharedPreferences` or logs)
- [ ] TLS pins present in `NetworkModule`, with a backup pin
- [ ] `bypass = BuildConfig.DEBUG` on `CertificatePinningInterceptor`
- [ ] `FLAG_SECURE` applied to screens showing sensitive data
- [ ] New backend secrets added to `.env.example`, GCP Secret Manager, and `cloud-run-deploy.yml`
- [ ] `REQUIRED_ENV_VARS` updated for mandatory secrets
- [ ] `Depends(get_current_user)` on every protected router endpoint
- [ ] Prompt injection pattern covered by a unit test if new patterns were added
- [ ] MCP tool call writes exactly one `AuditLog` entry
- [ ] No API keys or tokens committed to git (Gitleaks CI will block the PR)
- [ ] Security scan CI passes on the PR branch
