# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : security
# File    : email_service.py
# Purpose : Business logic for the email domain
#
# Architecture Layer : Security
# Pattern Used       : Service Layer (Business Logic)
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Transactional email service for security notifications.

Sends plaintext + HTML emails via SMTP (configured through application
settings).  This module is intentionally minimal: it handles only the
failed-login / account-lockout notification needed by Requirement 1.5.
Other notification types (RAG job complete, push notifications) are handled
by their own services.

SMTP configuration is read from:
    settings.SMTP_HOST, settings.SMTP_PORT
    settings.SMTP_USER, settings.SMTP_PASSWORD
    settings.SMTP_FROM_EMAIL

When ``SMTP_HOST`` is empty (the default in development / test environments),
the function logs the email content at DEBUG level and returns immediately
without making any network connection.  This allows the rest of the lockout
flow to work in environments where email is not configured.

Requirements: 1.5
"""

from __future__ import annotations

import logging
import smtplib
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText

logger = logging.getLogger(__name__)


async def send_failed_login_email(
    *,
    to_email: str,
    display_name: str,
    attempt_count: int,
    lockout_duration_minutes: int,
    remaining_lockout_seconds: int,
) -> None:
    """Send a security notification email about a failed login attempt.

    The email is sent synchronously inside this coroutine (``smtplib`` does
    not provide an async interface).  For high-throughput scenarios this call
    should be offloaded to a Celery task; for this implementation the blocking
    call is acceptable given that login endpoints are not on the hot path.

    Args:
        to_email:                  Recipient email address.
        display_name:              User's display name for personalisation
                                   (empty string falls back to the email).
        attempt_count:             Total failed attempts in the current window.
        lockout_duration_minutes:  Total lockout duration in minutes.
        remaining_lockout_seconds: Seconds remaining on the active lock
                                   (0 when the lock was just applied and the
                                   full duration is still remaining).

    Requirements: 1.5
    """
    from app.config.settings import get_settings  # local import — avoids circular deps

    settings = get_settings()

    if not settings.SMTP_HOST:
        # No SMTP configured — log and bail out silently
        logger.debug(
            "SMTP not configured; skipping failed-login notification",
            extra={
                "to_email": to_email,
                "attempt_count": attempt_count,
            },
        )
        return

    name = display_name or to_email
    remaining_minutes = max(1, remaining_lockout_seconds // 60)

    subject = "Security Alert: Failed Login Attempt on Your Account"

    plain_body = (
        f"Hi {name},\n\n"
        f"We detected a failed login attempt on your account ({to_email}).\n\n"
        f"Failed attempts in the last window: {attempt_count}\n"
        f"Account locked for: {lockout_duration_minutes} minutes\n"
        f"Approximate time remaining: {remaining_minutes} minute(s)\n\n"
        "If this was you, please wait until the lockout period expires and "
        "then log in again.\n\n"
        "If you did not attempt to log in, please reset your password "
        "immediately and contact support.\n\n"
        "— The AI Assistant Security Team"
    )

    html_body = f"""\
<html>
  <body>
    <p>Hi <strong>{name}</strong>,</p>
    <p>We detected a failed login attempt on your account (<em>{to_email}</em>).</p>
    <table>
      <tr><td><strong>Failed attempts in window:</strong></td><td>{attempt_count}</td></tr>
      <tr><td><strong>Account locked for:</strong></td>
          <td>{lockout_duration_minutes} minutes</td></tr>
      <tr><td><strong>Approx. time remaining:</strong></td>
          <td>{remaining_minutes} minute(s)</td></tr>
    </table>
    <p>
      If this was you, please wait until the lockout period expires and then
      log in again.<br/>
      If you did not attempt to log in, please
      <strong>reset your password immediately</strong> and contact support.
    </p>
    <p>— The AI Assistant Security Team</p>
  </body>
</html>"""

    msg = MIMEMultipart("alternative")
    msg["Subject"] = subject
    msg["From"] = settings.SMTP_FROM_EMAIL
    msg["To"] = to_email
    msg.attach(MIMEText(plain_body, "plain"))
    msg.attach(MIMEText(html_body, "html"))

    try:
        with smtplib.SMTP(settings.SMTP_HOST, settings.SMTP_PORT) as server:
            server.ehlo()
            server.starttls()
            server.ehlo()
            if settings.SMTP_USER and settings.SMTP_PASSWORD:
                server.login(settings.SMTP_USER, settings.SMTP_PASSWORD)
            server.sendmail(settings.SMTP_FROM_EMAIL, [to_email], msg.as_string())

        logger.info(
            "Failed-login notification sent",
            extra={"to_email": to_email, "attempt_count": attempt_count},
        )

    except smtplib.SMTPException as exc:
        # Re-raise so the caller's broad except in lockout.py catches it and
        # logs it without breaking the authentication flow.
        raise RuntimeError(f"SMTP error sending lockout notification: {exc}") from exc
