# Walkthrough - Security Patch for PyJWT & Cryptography

I have patched the backend security vulnerabilities by upgrading critical dependencies and adding regression tests.

## Changes Made

### Backend Dependencies

Updated [requirements.txt](file:///J:/Android/AndroidStudioProjects/Kiro/TestGithub/backend/requirements.txt) to pin secure versions of `PyJWT` and `cryptography`.

```diff
-# CVE-2026-32597 — unknown `crit` header extensions accepted instead of rejected
-#   (RFC 7515 §4.1.11 MUST violation); fix: >=2.12.0
-PyJWT[crypto]==2.12.0
+# CVE-2026-32597 — unknown `crit` header extensions accepted instead of rejected
+#   (RFC 7515 §4.1.11 MUST violation); fix: >=2.12.0
+# CVE-2026-48526 — Auth bypass due to forged JWTs; fix: >=2.13.0
+PyJWT[crypto]==2.13.0
 passlib[bcrypt]==1.7.4
 bcrypt==4.2.0
-# PYSEC-2026-35/1284/2141/3553/3554, GHSA-537c-gmf6-5ccf — fix: >=49.0.0
-cryptography==49.0.0
+# PYSEC-2026-35/1284/2141/3553/3554, GHSA-537c-gmf6-5ccf — fix: >=49.0.0
+# CVE-2026-69247 — High severity vulnerability; fix: >=50.0.0
+cryptography==50.0.0
```

### Security Regression Test

Added a new test case to [test_jwt_handler.py](file:///J:/Android/AndroidStudioProjects/Kiro/TestGithub/backend/tests/unit/test_jwt_handler.py) to ensure that tokens with unknown `crit` header extensions are rejected, addressing **CVE-2026-32597**.

## Verification Results

### Automated Tests
- **Status**: ✅ Passed (26 passed)
- **Command**: `pytest tests/unit/test_jwt_handler.py`

### Environment Check
- **PyJWT**: 2.13.0
- **cryptography**: 50.0.0

> [!TIP]
> To apply these changes to your local environment, run:
> `pip install -r backend/requirements.txt`
