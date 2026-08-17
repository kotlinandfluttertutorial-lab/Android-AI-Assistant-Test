# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/property
# File    : test_property_33_registration_input_validation.py
# Purpose : Property-based tests for registration input validation
#
# Architecture Layer : Test
# Pattern Used       : Hypothesis Property-Based Testing
#
# Key Concepts:
#   - Email must conform to a valid RFC 5321 address format
#   - Password must be between 12 and 128 characters
#   - Invalid inputs must be rejected with HTTP 422 before any user record
#     is persisted (validated at the Pydantic schema layer)
#
# Dependencies:
#   - hypothesis, pydantic, pytest
# ============================================================

"""Property-based tests for registration input validation.

Property 33: Registration Input Validation
**Validates: Requirements 1.1**

Strategy:
  - ``valid_email_strategy``: generates well-formed email addresses containing
    a local-part, "@", and a domain with a TLD.
  - ``invalid_email_strategy``: generates strings that are definitely not valid
    email addresses (no "@", or no domain part, or empty string).
  - ``valid_password_strategy``: generates strings of 12 to 128 characters.
  - ``invalid_password_strategy``: generates strings outside that range
    (0 to 11 characters, or > 128 characters).

Assertions:
  - 33A: Any (valid_email, valid_password) combination → ``RegisterRequest``
    parses without error.
  - 33B: Any (valid_email, invalid_password) combination → ``RegisterRequest``
    raises ``ValidationError``.
  - 33C: Any (invalid_email, valid_password) combination → ``RegisterRequest``
    raises ``ValidationError``.
  - 33D: Any (invalid_email, invalid_password) combination → ``RegisterRequest``
    raises ``ValidationError``.
  - 33E: ``RegisterRequest`` validation raises ``ValidationError`` (not any
    other exception) — i.e., invalid inputs never reach the database.

Requirements: 1.1
"""

from __future__ import annotations

import os

# ---------------------------------------------------------------------------
# Environment variables must be set BEFORE any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st
from pydantic import ValidationError

from app.schemas.auth import RegisterRequest

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_ASCII_ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
_LOCAL_EXTRA = "!#$%&'*+/=?^_`{|}~"
_LOCAL_CHARS = _ASCII_ALNUM + _LOCAL_EXTRA
"""Characters safe for email local parts (RFC 5321 unquoted)."""

_DOMAIN_INNER = _ASCII_ALNUM + "-"
"""Characters safe for domain label bodies (letters, digits, hyphen)."""


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Domain label: must start and end with alnum; body may contain hyphen.
# This avoids IDNA/RFC 5321 rejections for:
#   - labels starting with a hyphen
#   - non-ASCII codepoints in domain labels
_alnum_char = st.sampled_from(_ASCII_ALNUM)
_domain_label_strategy: st.SearchStrategy[str] = st.builds(
    lambda start, middle, end: start + middle + end,
    start=_alnum_char,
    middle=st.text(alphabet=_DOMAIN_INNER, min_size=0, max_size=20),
    end=_alnum_char,
)

# TLD: 2-6 ASCII letters only (no hyphens, no digits — per RFC 5321)
_tld_strategy: st.SearchStrategy[str] = st.text(
    alphabet="abcdefghijklmnopqrstuvwxyz",
    min_size=2,
    max_size=6,
)

# A well-formed email: non-empty local-part + "@" + label + "." + TLD.
# Produces addresses like "abc@example.com", "x1@my-host.org".
valid_email_strategy: st.SearchStrategy[str] = st.builds(
    lambda local, domain, tld: f"{local}@{domain}.{tld}",
    local=st.text(alphabet=_LOCAL_CHARS, min_size=1, max_size=20),
    domain=_domain_label_strategy,
    tld=_tld_strategy,
)

# Strings that definitely lack the "local@domain.tld" structure.
# Each variant breaks a different structural requirement.
invalid_email_strategy: st.SearchStrategy[str] = st.one_of(
    # No "@" at all
    st.text(
        alphabet=st.characters(blacklist_characters="@"),
        min_size=1,
        max_size=40,
    ),
    # "@" present but no dot in domain (e.g. "x@nodot")
    st.builds(
        lambda local, domain: f"{local}@{domain}",
        local=st.text(min_size=1, max_size=10),
        domain=st.text(
            alphabet=st.characters(blacklist_characters=".@"),
            min_size=1,
            max_size=15,
        ),
    ),
    # Empty string
    st.just(""),
    # Only "@"
    st.just("@"),
    # "@" at the start (no local part)
    st.builds(
        lambda domain: f"@{domain}",
        domain=st.text(min_size=1, max_size=15),
    ),
)

# Valid password: 12 to 128 characters
valid_password_strategy: st.SearchStrategy[str] = st.text(
    min_size=12,
    max_size=128,
)

# Invalid password: too short (0–11 chars) OR too long (>128 chars)
invalid_password_strategy: st.SearchStrategy[str] = st.one_of(
    st.text(min_size=0, max_size=11),  # too short (includes empty)
    st.text(min_size=129, max_size=200),  # too long
)


# ===========================================================================
# Property 33A — valid email + valid password → accepted
# **Validates: Requirements 1.1**
# ===========================================================================


@given(email=valid_email_strategy, password=valid_password_strategy)
@settings(
    max_examples=100,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.filter_too_much],
    deadline=None,
)
def test_property_33a_valid_email_and_password_accepted(
    email: str, password: str
) -> None:
    """**Validates: Requirements 1.1**

    Property 33A: For any combination of a valid RFC 5321 email address and a
    password of 12–128 characters, ``RegisterRequest`` MUST parse without
    raising a ``ValidationError``.

    This confirms that every legitimately formatted registration request passes
    Pydantic schema validation and would be forwarded to the service layer
    (i.e., a user record could be persisted if the email is unique).
    """
    # Pydantic's EmailStr normalises to lowercase; ensure we pass a string that
    # Pydantic recognises as structurally valid.
    try:
        req = RegisterRequest(email=email, password=password)
    except ValidationError as exc:
        pytest.fail(
            f"Property 33A violated: RegisterRequest raised ValidationError for "
            f"valid email={email!r} and valid password of length {len(password)}.\n"
            f"Details: {exc}"
        )

    # After successful parsing the email must be non-empty (normalised)
    assert req.email, "Property 33A violated: Parsed email is empty after validation."
    # Password must be preserved
    assert (
        len(req.password) >= 12
    ), f"Property 33A violated: Parsed password length {len(req.password)} < 12."
    assert (
        len(req.password) <= 128
    ), f"Property 33A violated: Parsed password length {len(req.password)} > 128."


# ===========================================================================
# Property 33B — valid email + invalid password → rejected (HTTP 422 layer)
# **Validates: Requirements 1.1**
# ===========================================================================


@given(email=valid_email_strategy, password=invalid_password_strategy)
@settings(
    max_examples=100,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.filter_too_much],
    deadline=None,
)
def test_property_33b_invalid_password_rejected(email: str, password: str) -> None:
    """**Validates: Requirements 1.1**

    Property 33B: For any combination of a valid email address and a password
    outside the 12–128 character range, ``RegisterRequest`` MUST raise
    ``ValidationError``.

    The raised exception MUST be ``pydantic.ValidationError`` (not any other
    exception type), confirming that invalid inputs are caught at the schema
    layer before any user record is persisted.
    """
    with pytest.raises(ValidationError) as exc_info:
        RegisterRequest(email=email, password=password)

    # ValidationError must mention the password field
    errors = exc_info.value.errors()
    field_names = {str(loc) for err in errors for loc in err.get("loc", [])}
    assert any("password" in name for name in field_names), (
        f"Property 33B violated: ValidationError did not reference 'password' field. "
        f"Errors: {errors}"
    )


# ===========================================================================
# Property 33C — invalid email + valid password → rejected (HTTP 422 layer)
# **Validates: Requirements 1.1**
# ===========================================================================


@given(email=invalid_email_strategy, password=valid_password_strategy)
@settings(
    max_examples=100,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.filter_too_much],
    deadline=None,
)
def test_property_33c_invalid_email_rejected(email: str, password: str) -> None:
    """**Validates: Requirements 1.1**

    Property 33C: For any combination of an invalid email address and a valid
    password, ``RegisterRequest`` MUST raise ``ValidationError``.

    The raised exception MUST be ``pydantic.ValidationError``, confirming that
    malformed email addresses are caught at the Pydantic schema layer and that
    no user record is ever created for such inputs (HTTP 422 response).
    """
    with pytest.raises(ValidationError) as exc_info:
        RegisterRequest(email=email, password=password)

    # ValidationError must mention the email field
    errors = exc_info.value.errors()
    field_names = {str(loc) for err in errors for loc in err.get("loc", [])}
    assert any("email" in name for name in field_names), (
        f"Property 33C violated: ValidationError did not reference 'email' field. "
        f"Email: {email!r}, Errors: {errors}"
    )


# ===========================================================================
# Property 33D — invalid email + invalid password → rejected (HTTP 422 layer)
# **Validates: Requirements 1.1**
# ===========================================================================


@given(email=invalid_email_strategy, password=invalid_password_strategy)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.filter_too_much],
    deadline=None,
)
def test_property_33d_both_fields_invalid_rejected(email: str, password: str) -> None:
    """**Validates: Requirements 1.1**

    Property 33D: For any combination of an invalid email address AND an
    invalid password, ``RegisterRequest`` MUST raise ``ValidationError``.

    No request in which both fields are invalid should ever proceed past
    schema validation.
    """
    with pytest.raises(ValidationError):
        RegisterRequest(email=email, password=password)


# ===========================================================================
# Property 33E — only ValidationError is raised (no unexpected exceptions)
# **Validates: Requirements 1.1**
# ===========================================================================


@given(
    email=st.text(min_size=0, max_size=100),
    password=st.text(min_size=0, max_size=200),
)
@settings(
    max_examples=150,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.filter_too_much],
    deadline=None,
)
def test_property_33e_schema_raises_only_validation_error(
    email: str, password: str
) -> None:
    """**Validates: Requirements 1.1**

    Property 33E: For any arbitrary (email, password) string pair,
    ``RegisterRequest`` MUST either:
      - Parse successfully (valid inputs), OR
      - Raise ``pydantic.ValidationError`` (invalid inputs).

    It MUST NOT raise any other exception type (e.g. ``TypeError``,
    ``AttributeError``, ``RuntimeError``).  This property ensures that the
    schema validation is robust against unexpected inputs and that no internal
    errors could leak database access or other side effects.
    """
    try:
        RegisterRequest(email=email, password=password)
    except ValidationError:
        # Expected for invalid inputs — acceptable outcome
        pass
    except Exception as exc:
        pytest.fail(
            f"Property 33E violated: RegisterRequest raised unexpected exception "
            f"{type(exc).__name__!r} (not ValidationError) for "
            f"email={email!r}, password of length {len(password)}.\n"
            f"Exception: {exc}"
        )


# ===========================================================================
# Property 33F — password boundary: length 12 accepted, length 11 rejected
# **Validates: Requirements 1.1**
# ===========================================================================


@given(
    email=valid_email_strategy,
    suffix=st.text(min_size=0, max_size=116),  # 128 - 12 = 116 extra chars allowed
)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.filter_too_much],
    deadline=None,
)
def test_property_33f_password_minimum_boundary(email: str, suffix: str) -> None:
    """**Validates: Requirements 1.1**

    Property 33F: The minimum password boundary MUST be exactly 12 characters.
    - A password of exactly 12 characters MUST be accepted.
    - A password of exactly 11 characters MUST be rejected.

    This confirms the boundary condition from Requirement 1.1 is enforced
    consistently regardless of the email value.
    """
    base = "A" * 12  # exactly 12 characters
    password_12 = (base + suffix)[:128]  # clamp to maximum

    # Exactly 12 characters (or padded up to 128) must be accepted
    try:
        RegisterRequest(email=email, password=password_12)
    except ValidationError as exc:
        pytest.fail(
            f"Property 33F violated: password of length {len(password_12)} should be "
            f"accepted (≥12), but raised ValidationError.\nDetails: {exc}"
        )

    # Exactly 11 characters must be rejected
    password_11 = "A" * 11
    with pytest.raises(ValidationError):
        RegisterRequest(email=email, password=password_11)


# ===========================================================================
# Property 33G — password maximum boundary: length 128 accepted, 129 rejected
# **Validates: Requirements 1.1**
# ===========================================================================


@given(email=valid_email_strategy)
@settings(
    max_examples=30,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.filter_too_much],
    deadline=None,
)
def test_property_33g_password_maximum_boundary(email: str) -> None:
    """**Validates: Requirements 1.1**

    Property 33G: The maximum password boundary MUST be exactly 128 characters.
    - A password of exactly 128 characters MUST be accepted.
    - A password of exactly 129 characters MUST be rejected.

    This validates the upper bound from Requirement 1.1.
    """
    # Exactly 128 characters must be accepted
    password_128 = "A" * 128
    try:
        RegisterRequest(email=email, password=password_128)
    except ValidationError as exc:
        pytest.fail(
            f"Property 33G violated: password of 128 chars should be accepted, "
            f"but raised ValidationError.\nDetails: {exc}"
        )

    # Exactly 129 characters must be rejected
    password_129 = "A" * 129
    with pytest.raises(ValidationError):
        RegisterRequest(email=email, password=password_129)
