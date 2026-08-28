# Security Guide
## Android AI Assistant — Enterprise Edition

---

## Overview

Security is not a feature added at the end — it is a design constraint on every layer of the
platform. This guide documents every security control, how it is implemented, and what it protects.

---

## JWT Lifecycle

### Token Types

| Token | Expiry | Storage (Android) | Storage (Backend) |
|-------|--------|-------------------|-------------------|
| Access Token | 15 minutes | EncryptedSharedPreferences | Stateless (JWT only) |
| Refresh Token | 30 days | EncryptedSharedPreferences | PostgreSQL `refresh_tokens` table (hashed) |

### Access Token Claims
```json
{
  "sub": "user_uuid",
  "email": "user@example.com",
  "role": "user",
  "iat": 1704067200,
  "exp": 1704068100
}
```

### Refresh Token Rotation

1. Client presents valid refresh token to `POST /auth/refresh`
2. Backend verifies token hash against `refresh_tokens` table
3. Backend issues **new** access token and **new** refresh token
4. Old refresh token is **immediately marked revoked** (`is_revoked = true`)
5. If a revoked token is ever presented again, **all tokens for that user are revoked** (replay protection)

### Replay Protection

If a previously-used (revoked) refresh token is submitted:
- All active refresh tokens for the user are immediately revoked
- An `REFRESH_TOKEN_REPLAY` audit log event is created
- The user is forced to re-authenticate

### JWT Validation

Every protected endpoint runs JWT validation via FastAPI `Depends(get_current_user)`:
1. Verify signature against `SECRET_KEY`
2. Check `exp` claim (HTTP 401 if expired)
3. Check `sub` maps to an active user (HTTP 401 if deactivated)
4. Return user object with role for RBAC check

---

## RBAC

Three roles are enforced on every protected endpoint:

| Role | Capabilities |
|------|-------------|
| `user` | All standard features (chat, RAG, notes, productivity, MCP tools) |
| `premium` | `user` + extended token limits, advanced analytics, priority LLM routing |
| `admin` | All `premium` capabilities + Admin Dashboard, user management, platform metrics |

Role enforcement is implemented as FastAPI dependencies:
```python
Depends(require_role("admin"))   # blocks user and premium
Depends(require_role("premium")) # blocks user only
Depends(get_current_user)        # blocks unauthenticated only
```

HTTP 403 is returned when the authenticated role lacks the required permission.

---

## Password Security

- Algorithm: **bcrypt** with minimum **work factor 12**
- Salt: automatically generated per-password by bcrypt
- Password minimum length: **12 characters**
- Passwords are **never** logged, returned in responses, or stored in plaintext
- Account lockout: **5 consecutive failed attempts within 10 minutes** → account locked for 15 minutes
- Lockout events are recorded in the audit log

---

## Android — EncryptedSharedPreferences

All locally stored credentials and tokens use `EncryptedSharedPreferences` (Jetpack Security):

```kotlin
// core-security: SecureStorage
class SecureStorage @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ai_assistant_secure_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun saveAccessToken(token: String) { prefs.edit().putString("access_token", token).apply() }
    fun getAccessToken(): String? = prefs.getString("access_token", null)
    fun clearAll() { prefs.edit().clear().apply() }
}
```

Keys are stored in the Android Keystore (hardware-backed on supported devices). Biometric data
**never leaves the device** — it is used only to unlock the Keystore key.

---

## Certificate Pinning

`CertificatePinningInterceptor` (OkHttp) rejects any connection whose server certificate does
not match the pinned SHA-256 fingerprint:

```kotlin
val pinned = CertificatePinner.Builder()
    .add("api.aiassistant.example.com", "sha256/XXXX...")
    .build()
```

**Updating pins:** When the backend certificate is renewed, a new app build must be distributed
with the updated pin before the old certificate expires (see `deployment-guide.md`).

---

## AES-256 API Key Storage

LLM provider API keys are encrypted at rest in PostgreSQL using AES-256-GCM:

```python
# security/encryption.py
class EncryptionService:
    def encrypt(self, plaintext: str) -> str:
        # AES-256-GCM with random 12-byte nonce; returns base64(nonce + ciphertext + tag)
        ...
    def decrypt(self, ciphertext: str) -> str: ...
```

The encryption key is loaded from the `ENCRYPTION_KEY` environment variable (256-bit, never committed).
API keys are **never** returned in plaintext in any API response.

---

## Prompt Injection Detection

`SafetyService.detect_prompt_injection(text: str) -> bool` detects patterns including:

- `ignore (previous|above|all) instructions`
- `you are now / act as / pretend (you are|to be)`
- `repeat your (system|instructions|rules)`
- `[INST]` / `<|system|>` injection delimiters
- DAN, STAN, JAILBREAK, and similar prompt patterns

On detection:
1. HTTP 400 returned to the client
2. Input is **not forwarded** to any LLM provider
3. Event logged to `audit_logs` with `event_type = "prompt_injection_blocked"`

---

## Input Sanitisation

`InputSanitizer` is applied to all user-supplied strings before database writes:
- Strips SQL injection payloads (`'; DROP TABLE`, `' OR 1=1`, etc.)
- Strips XSS vectors (`<script>`, `javascript:`, `onerror=`, etc.)
- Normalises Unicode to prevent homoglyph attacks

SQLAlchemy parameterized queries are used throughout — string interpolation into SQL is never done.

---

## Audit Log

Every security-relevant event is written to `audit_logs`:

| Event Type | Trigger |
|-----------|---------|
| `login` | Successful password or OAuth login |
| `login_failed` | Failed login attempt |
| `account_locked` | 5th consecutive failed attempt |
| `logout` | Explicit logout |
| `token_refresh` | Successful refresh token exchange |
| `refresh_token_replay` | Revoked token presented |
| `mcp_invoke` | Any MCP tool invocation (with tool name and status) |
| `prompt_injection_blocked` | Injection pattern detected |
| `user_deactivated` | Admin deactivates a user |
| `role_changed` | Admin changes a user's role |

**Retention:** Minimum **90 days**. Records are soft-deleted (the `created_at` index allows efficient purging via a scheduled Celery task).

---

## Rate Limiting

- **Limit:** 60 requests per minute per authenticated user
- **Implementation:** Redis sliding window counter keyed by `user_id`
- **Response on breach:** HTTP 429 with `Retry-After` header
- **Unauthenticated endpoints** (`/auth/login`, `/auth/register`) have a separate IP-based limit (10 req/min per IP)

---

## API Rate Limiting Against LLM Providers

- Per-provider rate limits are loaded from configuration
- When a limit is exceeded, a structured error is returned to the user identifying the provider
- Provider rate limit errors are counted in Prometheus metrics for visibility

---

## Root Detection (Android)

`RootDetectionUtil` checks for indicators of device rooting:
- Presence of `su` binary or Magisk files
- Build tag `test-keys`
- Dangerous system apps (SuperSU, BusyBox)

On detection, a warning dialog is shown. The app continues to function but logs a `rooted_device` event.
