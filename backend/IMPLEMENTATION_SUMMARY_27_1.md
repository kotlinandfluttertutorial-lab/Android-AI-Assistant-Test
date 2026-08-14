# Task 27.1 Implementation Summary

## Overview
Implemented JWT issuance, validation, refresh, and rotation with replay detection for the Android AI Assistant backend.

## Components Implemented

### 1. JWT Handler (`app/security/jwt_handler.py`)
- **`create_access_token(user_id, role, expires_delta=None) -> str`**
  - Signs JWT with HS256 algorithm
  - Claims: `sub`, `role`, `jti` (unique token ID), `iat`, `exp`
  - Default expiry: 15 minutes (configurable via `settings.ACCESS_TOKEN_EXPIRE_MINUTES`)

- **`verify_access_token(token: str) -> TokenPayload`**
  - Validates signature and expiry
  - Raises `InvalidTokenError` on failure
  - Returns parsed `TokenPayload` with all claims

- **`create_refresh_token(family_id=None, expires_delta=None) -> RefreshTokenData`**
  - Generates 256-bit URL-safe random token
  - Returns SHA-256 hash (for DB persistence), raw token, expiry, and family_id
  - Default expiry: 30 days (configurable via `settings.REFRESH_TOKEN_EXPIRE_DAYS`)

- **`hash_token(raw_token: str) -> str`**
  - SHA-256 hex digest for token lookup

### 2. Exceptions (`app/security/exceptions.py`)
- **`AuthError`** — Base class for all auth errors
- **`InvalidTokenError`** — Token missing, malformed, expired, or revoked (maps to HTTP 401)
- **`SecurityViolationError`** — Security attack detected (maps to HTTP 401)
- **`TokenFamilyRevokedError`** — Replay detected, all family tokens revoked (subclass of SecurityViolationError)

### 3. Refresh Token Repository (`app/repositories/refresh_token_repository.py`)
- **`create(...) -> RefreshToken`** — Persist new refresh token record
- **`get_by_hash(token_hash: str) -> RefreshToken | None`** — Lookup by SHA-256 hash (with eager-loaded user relationship)
- **`mark_used(token_id) -> None`** — Mark token as used (not yet revoked)
- **`revoke(token_id) -> None`** — Permanently revoke a single token
- **`revoke_family(family_id) -> int`** — Revoke ALL tokens in a rotation chain (used on replay detection)
- **`revoke_all_for_user(user_id) -> int`** — Revoke all active tokens for a user (used on logout)

### 4. Auth Service (`app/services/auth_service.py`)
- **`issue_tokens_for_user(db, user_id, role) -> (access_token, refresh_token)`**
  - Called after successful login
  - Issues new JWT + refresh token pair
  - Starts a new token family

- **`refresh_tokens(db, raw_refresh_token) -> (new_access, new_refresh, role, user_id)`**
  - Token rotation with replay detection:
    1. Hash and lookup token
    2. If not found → `InvalidTokenError`
    3. If revoked → `InvalidTokenError`
    4. If expired → `InvalidTokenError`
    5. If used → **REPLAY DETECTED** → revoke entire family → `TokenFamilyRevokedError`
    6. If valid → mark used, issue new JWT + refresh token (same family, parent_id = old token)
  
- **`logout_user(db, user_id) -> int`**
  - Revokes all active refresh tokens for the user

### 5. Database Model Updates
- Updated `RefreshToken` model to add `parent_token_id` column (FK to self, tracks token chain)
- Updated `app/models/__init__.py` to export `RefreshToken`

### 6. Alembic Migration (`alembic/versions/0002_add_refresh_tokens.py`)
Creates `refresh_tokens` table with:
- `id` (UUID, PK)
- `token_hash` (String(64), unique, indexed)
- `user_id` (UUID, FK to users, CASCADE delete)
- `family_id` (UUID, indexed for family revocation)
- `parent_token_id` (UUID, FK to self, SET NULL)
- `expires_at` (DateTime with timezone)
- `used` (Boolean, default False)
- `revoked` (Boolean, default False)
- `created_at`, `updated_at` (timestamps)

Indexes:
- Unique on `token_hash`
- On `user_id`, `family_id`, `expires_at`
- Composite on (`user_id`, `revoked`)

### 7. Configuration (`app/config/settings.py`)
Added property aliases:
- `JWT_SECRET_KEY` → `SECRET_KEY`
- `JWT_ACCESS_TOKEN_EXPIRE_MINUTES` → `ACCESS_TOKEN_EXPIRE_MINUTES`
- `JWT_REFRESH_TOKEN_EXPIRE_DAYS` → `REFRESH_TOKEN_EXPIRE_DAYS`

Existing fields:
- `SECRET_KEY` (required, min 32 chars)
- `JWT_ALGORITHM` (default "HS256")
- `ACCESS_TOKEN_EXPIRE_MINUTES` (default 15)
- `REFRESH_TOKEN_EXPIRE_DAYS` (default 30)

### 8. Unit Tests

#### `tests/unit/test_jwt_handler.py` (21 tests)
- `create_access_token`: returns string, sub matches user_id, role present, jti is UUID, unique jti per call, ~15 min expiry, custom expiry override
- `verify_access_token`: happy path, raises on expired, raises on tampered signature, raises on wrong secret, raises on malformed, raises on missing required claim
- `create_refresh_token`: returns RefreshTokenData, hash is SHA-256 of raw, hash length 64, ~30 days expiry, custom family_id preserved, new family_id when None, unique raw values, custom expiry override
- `hash_token`: deterministic, matches SHA-256, length 64, different inputs produce different hashes

#### `tests/unit/test_auth_service_refresh.py` (21 tests using AsyncMock)
- `issue_tokens_for_user`: returns two strings, JWT has correct claims, repo.create called once, repo.create called with no parent
- `refresh_tokens` happy path: returns new JWT + refresh, old token marked used, new token persisted with same family, new JWT has correct claims
- `refresh_tokens` replay detection: raises TokenFamilyRevokedError on replay, revoke_family called on replay, no new tokens issued on replay
- `refresh_tokens` error cases: raises on nonexistent token, raises on revoked token, raises on expired token, raises on inactive user
- `logout_user`: delegates to revoke_all_for_user, returns zero when no tokens

## Requirements Coverage

- **Requirement 1.2** (JWT + refresh token issuance): ✅ `create_access_token`, `create_refresh_token`, `issue_tokens_for_user`
- **Requirement 1.3** (Refresh without re-auth): ✅ `refresh_tokens` function
- **Requirement 1.4** (Token rotation + replay detection): ✅ `refresh_tokens` with family revocation on replay
- **Requirement 1.10** (Logout invalidates tokens): ✅ `logout_user` function

## Testing Instructions

1. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```

2. Run unit tests:
   ```bash
   pytest backend/tests/unit -v
   ```

3. Run with coverage:
   ```bash
   pytest backend/tests/unit --cov=app.security --cov=app.services.auth_service --cov-report=term-missing
   ```

## Integration Notes

- All functions use async/await and accept `AsyncSession` for database operations
- Repository uses `selectinload` to eagerly load the `User` relationship (avoids lazy-load issues in async context)
- Service layer does NOT interact with HTTP — route handlers translate service results to HTTP responses
- Token hashing is performed before calling repository methods (repository never sees raw tokens)
- JWT signature validation is pure (no I/O) — JTI revocation checks happen in middleware layer
- Settings use pydantic-settings with env var overrides

## Security Properties

1. **Refresh tokens never stored in plaintext** — only SHA-256 hash persisted
2. **JTI (unique token ID) per JWT** — enables per-token revocation
3. **Single-use refresh tokens** — marked `used` after exchange
4. **Replay detection** — entire token family revoked on replay attempt
5. **Expiry validation** — tokens checked against current time and DB timestamp
6. **Inactive user check** — refresh fails if user account deactivated

## Files Created / Modified

### Created:
- `backend/app/security/jwt_handler.py`
- `backend/app/security/exceptions.py`
- `backend/app/repositories/refresh_token_repository.py`
- `backend/app/services/auth_service.py`
- `backend/alembic/versions/0002_add_refresh_tokens.py`
- `backend/tests/unit/test_jwt_handler.py`
- `backend/tests/unit/test_auth_service_refresh.py`
- `backend/tests/conftest.py`
- `backend/tests/README.md`
- `backend/pytest.ini`

### Modified:
- `backend/app/models/refresh_token.py` (added `parent_token_id` column)
- `backend/app/models/__init__.py` (export RefreshToken)
- `backend/app/config/settings.py` (added JWT property aliases)
- `backend/app/security/__init__.py` (export public API)

## Next Steps

Task 27.1 is complete. The following tasks are marked partially done or not started:
- Task 27.3: JWT validation middleware (uses `verify_access_token` from this task)
- Task 27.5: RBAC middleware (uses JWT role claim from this task)
- Auth router implementation (calls `issue_tokens_for_user`, `refresh_tokens`, `logout_user`)
