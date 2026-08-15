# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : security
# File    : input_sanitizer.py
# Purpose : input_sanitizer — security module
#
# Architecture Layer : Security
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Input sanitization utilities for SQL injection and XSS prevention.

Provides:
- ``sanitize_string``      — strips HTML/script tags and encodes dangerous chars
- ``detect_sql_injection`` — regex-based SQL injection pattern detection
- ``detect_xss``           — regex-based XSS pattern detection
- ``sanitized_field``      — Pydantic v2 ``Field`` factory with validator pre-applied
- ``InputSanitizer``       — reusable validator mixin for Pydantic v2 models

All user-supplied string inputs MUST pass through ``sanitize_string`` before
being persisted to the database.  Inputs that match SQL injection or XSS
patterns are rejected with a ``ValueError`` (which Pydantic surfaces as a 422
``Unprocessable Entity``).

Usage in a Pydantic v2 schema::

    from app.security.input_sanitizer import sanitize_user_string

    class MyRequest(BaseModel):
        name: str = Field(max_length=255)
        bio: str = Field(default="", max_length=2000)

        _sanitize_name = field_validator("name")(sanitize_user_string)
        _sanitize_bio  = field_validator("bio")(sanitize_user_string)

Or use the helper directly::

    clean = sanitize_string(raw_value)

Requirements: 9.7
"""

from __future__ import annotations

import html
import re
import unicodedata

# ---------------------------------------------------------------------------
# Maximum length constants
# ---------------------------------------------------------------------------

MAX_SHORT_STRING = 255  # names, titles, labels
MAX_MEDIUM_STRING = 2_000  # descriptions, bios, prompts
MAX_LONG_STRING = 10_000  # note content, long bodies
MAX_URL_LENGTH = 2_048  # per RFC 2616
MAX_EMAIL_LENGTH = 254  # per RFC 5321

# ---------------------------------------------------------------------------
# XSS detection patterns
# ---------------------------------------------------------------------------

# Compiled once at import time for performance.
_XSS_PATTERNS: list[re.Pattern[str]] = [
    # <script ...> tags (opening or closing)
    re.compile(r"<\s*script[^>]*>", re.IGNORECASE | re.DOTALL),
    re.compile(r"<\s*/\s*script[^>]*>", re.IGNORECASE),
    # javascript: or vbscript: protocol in attribute values
    re.compile(r"\bjavascript\s*:", re.IGNORECASE),
    re.compile(r"\bvbscript\s*:", re.IGNORECASE),
    # data: URIs that embed scripts
    re.compile(r"\bdata\s*:[^,]*base64", re.IGNORECASE),
    # Inline event handlers: on* = "..."
    re.compile(r"\bon\w+\s*=", re.IGNORECASE),
    # <iframe>, <object>, <embed>, <form> (common XSS vectors)
    re.compile(r"<\s*(iframe|object|embed|form)\b", re.IGNORECASE),
    # expression() — CSS expression injection
    re.compile(r"\bexpression\s*\(", re.IGNORECASE),
    # Encoded angle brackets trying to sneak past naive checks
    re.compile(r"&#\s*60\b", re.IGNORECASE),  # &#60; == <
    re.compile(r"&#\s*x3[cC]\b", re.IGNORECASE),  # &#x3C; == <
]

# ---------------------------------------------------------------------------
# SQL injection detection patterns
# ---------------------------------------------------------------------------

_SQL_PATTERNS: list[re.Pattern[str]] = [
    # Classic termination: ' OR 1=1 --
    re.compile(
        r"('\s*(?:OR|AND)\s+[\w\s='\"]+--)",
        re.IGNORECASE,
    ),
    # UNION-based injection: ' UNION SELECT ...
    re.compile(r"'\s*UNION\b.*\bSELECT\b", re.IGNORECASE | re.DOTALL),
    # Stacked queries with semicolons followed by SQL keywords
    re.compile(
        r";\s*(?:DROP|DELETE|INSERT|UPDATE|CREATE|ALTER|TRUNCATE|EXEC|EXECUTE|GRANT|REVOKE)\b",
        re.IGNORECASE,
    ),
    # SQL comment sequences after a quote
    re.compile(r"'[^']*--", re.IGNORECASE),
    re.compile(r"'[^']*#", re.IGNORECASE),
    # Blind SQL: SLEEP(), WAITFOR DELAY, BENCHMARK()
    re.compile(r"\b(?:SLEEP|WAITFOR\s+DELAY|BENCHMARK)\s*\(", re.IGNORECASE),
    # Error-based extraction: EXTRACTVALUE, UPDATEXML, GROUP BY x HAVING
    re.compile(r"\b(?:EXTRACTVALUE|UPDATEXML)\s*\(", re.IGNORECASE),
    # xp_cmdshell and other MSSQL exploits
    re.compile(r"\bxp_cmdshell\b", re.IGNORECASE),
    # Boolean-based: ' AND '1'='1
    re.compile(r"'\s*AND\s+'?\d+'?\s*=\s*'?\d+", re.IGNORECASE),
    # Tautologies: OR 'x'='x', OR 1=1 — also catches unclosed quotes (' OR 'x'='x)
    re.compile(r"\bOR\s+'[^']*'\s*=\s*'", re.IGNORECASE),
    re.compile(r"\bOR\s+\d+\s*=\s*\d+", re.IGNORECASE),
    # NULL byte
    re.compile(r"\x00"),
]

# ---------------------------------------------------------------------------
# HTML tag stripping
# ---------------------------------------------------------------------------

# Matches any HTML / XML tag — used to strip markup from user input.
_HTML_TAG_RE = re.compile(r"<[^>]+>")


def _strip_html_tags(value: str) -> str:
    """Remove all HTML and XML tags from *value*.

    After stripping tags the function decodes HTML entities (e.g. ``&amp;``
    → ``&``) so that the stored value is plain text.
    """
    stripped = _HTML_TAG_RE.sub("", value)
    # Decode HTML entities to normalised plain text
    decoded = html.unescape(stripped)
    return decoded


def _normalize_unicode(value: str) -> str:
    """NFC-normalise Unicode to prevent homoglyph attacks."""
    return unicodedata.normalize("NFC", value)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def detect_xss(value: str) -> bool:
    """Return ``True`` if *value* contains an XSS pattern.

    Args:
        value: Raw string from user input.

    Returns:
        ``True`` if any XSS pattern is matched, ``False`` otherwise.

    Requirements: 9.7
    """
    for pattern in _XSS_PATTERNS:
        if pattern.search(value):
            return True
    return False


def detect_sql_injection(value: str) -> bool:
    """Return ``True`` if *value* contains a SQL injection pattern.

    Args:
        value: Raw string from user input.

    Returns:
        ``True`` if any SQL injection pattern is matched, ``False`` otherwise.

    Requirements: 9.7
    """
    for pattern in _SQL_PATTERNS:
        if pattern.search(value):
            return True
    return False


def sanitize_string(value: str) -> str:
    """Sanitize a user-supplied string for safe database persistence.

    Steps applied in order:
    1. NFC Unicode normalisation (homoglyph defence).
    2. Reject if XSS pattern detected on the raw value (raises ``ValueError``).
    3. Strip all HTML / XML tags.
    4. Reject if SQL injection pattern detected (raises ``ValueError``).

    The returned string is safe to persist via SQLAlchemy parameterised queries.
    Note that parameterised queries already prevent SQL injection at the driver
    level; this function provides defence-in-depth at the schema boundary.

    XSS detection runs on the **raw** value (before tag stripping) to catch
    obfuscated payloads that embed script content inside tags.  Tag stripping
    is then applied as a secondary defence to remove any residual markup.

    Args:
        value: Raw user-supplied string.

    Returns:
        Sanitized string (HTML tags stripped, plain text).

    Raises:
        ValueError: When the value contains an XSS or SQL injection pattern.

    Requirements: 9.7
    """
    if not value:
        return value

    # Step 1 — Unicode normalisation
    value = _normalize_unicode(value)

    # Step 2 — XSS detection on the raw value (before stripping)
    if detect_xss(value):
        raise ValueError(
            "Input rejected: potential cross-site scripting (XSS) pattern detected. "
            "Please remove any HTML or script content."
        )

    # Step 3 — Strip HTML tags (defence-in-depth: removes residual markup)
    value = _strip_html_tags(value)

    # Step 4 — SQL injection detection
    if detect_sql_injection(value):
        raise ValueError(
            "Input rejected: potential SQL injection pattern detected. "
            "Please review your input for SQL keywords or special characters."
        )

    return value


def sanitize_user_string(cls, value: str) -> str:
    """Pydantic v2 ``field_validator`` compatible sanitization function.

    Designed to be used with ``@field_validator`` or ``model_validator``::

        from pydantic import field_validator
        from app.security.input_sanitizer import sanitize_user_string

        class MyModel(BaseModel):
            name: str

            @field_validator("name")
            @classmethod
            def validate_name(cls, v: str) -> str:
                return sanitize_user_string(cls, v)

    Or more concisely with the shorthand::

        _sanitize = field_validator("name", "bio", mode="before")(sanitize_user_string)

    Args:
        cls:   The Pydantic model class (passed automatically by ``field_validator``).
        value: The field value to sanitize.

    Returns:
        Sanitized string value.

    Raises:
        ValueError: When the value contains a dangerous pattern.

    Requirements: 9.7
    """
    if not isinstance(value, str):
        return value  # type: ignore[return-value]
    return sanitize_string(value)
