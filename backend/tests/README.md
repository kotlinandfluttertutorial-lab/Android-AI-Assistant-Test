# Backend Unit Tests

## Setup

1. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```

2. Run all unit tests:
   ```bash
   pytest backend/tests/unit -v
   ```

3. Run with coverage:
   ```bash
   pytest backend/tests/unit --cov=app --cov-report=term-missing
   ```

## Test Structure

- `tests/unit/test_jwt_handler.py` — Tests for JWT creation and verification
- `tests/unit/test_auth_service_refresh.py` — Tests for refresh token rotation and replay detection

## Test Data

All tests use mock data and do not require a live database. Environment variables are set in `tests/conftest.py`.

## Requirements Covered

- **1.2**: JWT and refresh token issuance with correct expiry times
- **1.3**: Token refresh without re-authentication
- **1.4**: Token rotation and replay detection with family revocation
- **1.10**: Logout (revoke all tokens for user)
