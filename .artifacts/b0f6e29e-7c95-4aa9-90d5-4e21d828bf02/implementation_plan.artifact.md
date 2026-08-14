# Implementation Plan - Security Patch: PyJWT & Cryptography Vulnerabilities

This plan addresses several high-severity vulnerabilities identified in the backend Python dependencies.

## Vulnerabilities to Address

1.  **PyJWT**:
    *   **CVE-2026-32597**: Accepts unknown `crit` header extensions (RFC 7515 violation). Fixed in `2.12.0`.
    *   **CVE-2026-48526**: Authentication bypass due to forged JSON Web Tokens. Fixed in `2.13.0`.
2.  **Cryptography**:
    *   **CVE-2026-69247**: High severity vulnerability fixed in `50.0.0`.

## User Review Required

> [!IMPORTANT]
> The `backend/requirements.txt` file on disk has unsaved changes from a previous attempt. I will overwrite it with the correct fixed versions (`PyJWT==2.13.0` and `cryptography==50.0.0`).

## Proposed Changes

### Backend Dependencies

#### [MODIFY] [requirements.txt](file:///J:/Android/AndroidStudioProjects/Kiro/TestGithub/backend/requirements.txt)
- Update `PyJWT[crypto]` to `2.13.0`.
- Update `cryptography` to `50.0.0`.
- Update security comments to include the new CVE identifiers.

### Security Regression Testing

#### [MODIFY] [test_jwt_handler.py](file:///J:/Android/AndroidStudioProjects/Kiro/TestGithub/backend/tests/unit/test_jwt_handler.py)
- Add a new test case `test_raises_on_unknown_crit_header` to `TestVerifyAccessToken`. This specifically targets CVE-2026-32597 to ensure the fix is active.

## Verification Plan

### Automated Tests
- Run unit tests for the JWT handler:
```powershell
cd backend
./venv311/Scripts/pytest.exe tests/unit/test_jwt_handler.py
```

### Manual Verification
- Review the `requirements.txt` file to ensure all pins are correct.
