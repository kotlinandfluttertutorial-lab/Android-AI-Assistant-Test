# Security Guide
## Android AI Assistant — Enterprise Edition

---

## Overview

Security is a design constraint on every layer of the platform, not a feature added at the end.
This guide documents every security control, how it is implemented, and what it protects against.

---

## JWT Lifecycle

### Token Types

| Token | Expiry | Storage (Android) | Storage (Backend) |
|-------|--------|-------------------|-------------------|
| Access Token | 15 minutes | EncryptedSharedPreferences | Stateless (JWT only) |
| Refresh Token | 30 days | EncryptedSharedPreferences | PostgreSQL `refresh_tokens` table (SHA-256 hashed) |

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
5. New refresh token shares the same `family_id` as the old one

### Replay Protection (Token Family Attack Prevention)

If a previously-used (revoked) refresh token is presented:
1. All active refresh tokens sharing the same `family_id` are revoked
2. A `REFRESH_TOKEN_REPLAY` event is written to the audit log
3. The user is forced to re-authenticate on all devices

### JWT Validation (every protected endpoint)

```python
# FastAPI Depends chain
Depends(get_current_user)  →  verify signature  →  check exp  →  check sub is active user
```

HTTP 401 on any validation failure. HTTP 403 when role is insufficient.

---

## RBAC

Three roles enforced on every protected endpoint:

| Role | Capabilities |
|------|-------------|
| `user` | All standard features (chat, RAG, notes, productivity, MCP tools) |
| `premium` | `user` + extended token limits, advanced analytics, priority LLM routing |
| `admin` | All `premium` + Admin Dashboard, user management, platform metrics |

```python
# FastAPI dependency factories
Depends(require_role("admin"))    # blocks user and premium
Depends(require_role("premium"))  # blocks user only
Depends(get_current_user)         # blocks unauthenticated only
```

HTTP 403 returned when the authenticated role lacks the required permission.

---

## Password Security

| Control | Value |
|---------|-------|
| Algorithm | bcrypt |
| Work factor | Minimum 12 |
| Salt | Generated per-password by bcrypt |
| Minimum length | 12 characters |
| Maximum length | 128 characters |
| Storage | Never plaintext — bcrypt hash only |
| Logging | Never logged or returned in any response |

**Account lockout:** 5 consecutive failed attempts within 10 minutes → account locked for
15 minutes. Each attempt triggers one email notification. The lockout event is recorded in
the audit log with `event_type = "account_locked"`.

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

    fun saveAccessToken(token: String) = prefs.edit().putString("access_token", token).apply()
    fun getAccessToken(): String? = prefs.getString("access_token", null)
    fun clearAll() = prefs.edit().clear().apply()
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
    .add("api.aiassistant.example.com", "sha256/AAAA...") // primary
    .add("api.aiassistant.example.com", "sha256/BBBB...") // backup pin
    .build()
```

**Updating pins:** When the backend TLS certificate is renewed, a new app build with the updated
pin must be distributed before the old certificate expires. Always include at least one backup pin
to avoid lockout during transitions. See `DEPLOYMENT_GUIDE.md` for the update procedure.

---

## AES-256 API Key Storage

LLM provider API keys are encrypted at rest using AES-256-GCM:

```python
# security/encryption.py
class EncryptionService:
    def encrypt(self, plaintext: str) -> str:
        # Random 12-byte nonce; returns base64(nonce + ciphertext + auth_tag)
        nonce = os.urandom(12)
        cipher = AESGCM(self._key)
        ciphertext = cipher.encrypt(nonce, plaintext.encode(), None)
        return base64.b64encode(nonce + ciphertext).decode()

    def decrypt(self, encoded: str) -> str:
        data = base64.b64decode(encoded)
        nonce, ciphertext = data[:12], data[12:]
        cipher = AESGCM(self._key)
        return cipher.decrypt(nonce, ciphertext, None).decode()
```

- Encryption key loaded from `ENCRYPTION_KEY` environment variable (256-bit, never committed)
- Non-deterministic (random nonce) — two encryptions of the same key produce different ciphertexts
- API keys **never returned in plaintext** in any API response or log

---

## Prompt Injection Detection

`SafetyService.detect_prompt_injection(text: str) -> bool` detects patterns including:

- `ignore (previous|above|all) instructions`
- `you are now / act as / pretend (you are|to be)`
- `repeat your (system|instructions|rules)`
- `[INST]` / `<|system|>` injection delimiters
- DAN, STAN, JAILBREAK, and similar jailbreak payloads

**On detection:**
1. HTTP 400 returned with `error.code = "PROMPT_INJECTION_DETECTED"`
2. Input is **not forwarded** to any LLM provider
3. Audit log entry written with `event_type = "prompt_injection_blocked"`, user ID, timestamp,
   and SHA-256 hash of sanitised input (never raw text)

---

## Input Sanitisation

`InputSanitizer` applied to all user-supplied strings before database writes:
- Strips SQL injection payloads (`'; DROP TABLE`, `' OR 1=1`, etc.)
- Strips XSS vectors (`<script>`, `javascript:`, `onerror=`, etc.)
- Normalises Unicode to prevent homoglyph attacks

SQLAlchemy parameterised queries are used throughout — raw string interpolation into SQL is
never performed.

---

## Rate Limiting

| Scope | Limit | Response |
|-------|-------|---------|
| Authenticated users | 60 req/min (per `user_id`) | HTTP 429 + `Retry-After` header |
| Public endpoints (`/auth/login`, `/auth/register`) | 20 req/min per IP | HTTP 429 + `Retry-After` header |

Implementation: Redis sliding window counter. Keyed by `user_id` for authenticated requests,
by IP address for public endpoints.

---

## Audit Log

All security-relevant events are written to the `audit_logs` table:

| Event Type | Trigger |
|-----------|---------|
| `login` | Successful password or OAuth login |
| `login_failed` | Failed login attempt |
| `account_locked` | 5th consecutive failed attempt within 10 min |
| `logout` | Explicit logout |
| `token_refresh` | Successful refresh token exchange |
| `refresh_token_replay` | Revoked token presented (triggers family revocation) |
| `mcp_invoke` | Any MCP tool invocation (tool name + status) |
| `prompt_injection_blocked` | Injection pattern detected |
| `user_deactivated` | Admin deactivates a user |
| `role_changed` | Admin changes a user's role |

**Retention:** Minimum 90 days. Automated purge via scheduled Celery task (`cleanup_old_audit_logs`).

---

## Root Detection (Android)

`RootDetectionUtil` checks for indicators of device rooting:
- Presence of `su` binary or Magisk files
- Build tag `test-keys`
- Dangerous system apps (SuperSU, BusyBox)

On detection a warning dialog is shown. The app continues to function but logs a
`rooted_device_detected` event. Organisations may enforce stricter behaviour via Firebase
Remote Config.

---

## Biometric Authentication

`BiometricAuthManager` wraps `BiometricPrompt`:
- Unlocks the local Keystore key to decrypt tokens from `SecureStorage`
- Biometric data never leaves the device
- Session valid for 15 minutes; re-prompt on expiry
- Falls back to device PIN/password if biometric is unavailable

---

## Network Security Configuration (Android)

`res/xml/network_security_config.xml` enforces:
- TLS required for all connections
- Certificate pinning for `api.aiassistant.example.com`
- Cleartext traffic blocked for all domains (no HTTP fallback)

---

## WebSocket Security

- JWT is validated on the WebSocket connection upgrade (HTTP 401 → connection refused)
- Token transmitted via query parameter `?token=<JWT>` (TLS protected; never in browser URL bar on Android)
- Connection closed with code `4001` on auth failure
- Heartbeat (ping every 30 s) with 10-second timeout closes stale connections (code `1001`)
