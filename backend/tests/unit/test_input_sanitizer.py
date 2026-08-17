"""Unit tests for app.security.input_sanitizer.

Covers:
- detect_xss: returns True for XSS patterns, False for clean inputs
- detect_sql_injection: returns True for SQL injection patterns, False for clean inputs
- sanitize_string: strips HTML tags, raises ValueError for XSS/SQLi patterns, returns clean strings
- sanitize_user_string: Pydantic field_validator compatible wrapper
- Schema integration: RegisterRequest, ConversationCreate, TodoCreate, DocumentQueryRequest,
  HabitCreate, ReminderCreate, CalendarEventCreate are all validated at model instantiation

Requirements: 9.7
"""

from __future__ import annotations

import os

import pytest
from pydantic import ValidationError

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from app.security.input_sanitizer import (
    detect_sql_injection,
    detect_xss,
    sanitize_string,
    sanitize_user_string,
)

# ---------------------------------------------------------------------------
# detect_xss
# ---------------------------------------------------------------------------


class TestDetectXss:
    """Tests for detect_xss(value) -> bool."""

    def test_clean_text_returns_false(self) -> None:
        assert detect_xss("Hello, world! This is normal text.") is False

    def test_script_tag_detected(self) -> None:
        assert detect_xss('<script>alert("xss")</script>') is True

    def test_script_tag_with_attributes_detected(self) -> None:
        assert detect_xss('<script type="text/javascript">evil()</script>') is True

    def test_closing_script_tag_detected(self) -> None:
        assert detect_xss("</script>") is True

    def test_closing_script_tag_with_trailing_space_detected(self) -> None:
        # CodeQL: closing tags like </script > must also be caught
        assert detect_xss("</script >") is True
        assert detect_xss("</SCRIPT >") is True
        assert detect_xss("</script\t>") is True

    def test_javascript_colon_detected(self) -> None:
        assert detect_xss("javascript:void(0)") is True

    def test_javascript_colon_case_insensitive(self) -> None:
        assert detect_xss("JAVASCRIPT:alert(1)") is True

    def test_vbscript_colon_detected(self) -> None:
        assert detect_xss("vbscript:msgbox(1)") is True

    def test_data_uri_base64_detected(self) -> None:
        assert detect_xss("data:text/html;base64,PHNjcmlwdD4=") is True

    def test_inline_event_handler_detected(self) -> None:
        assert detect_xss('<img src="x" onerror="alert(1)">') is True

    def test_onclick_event_detected(self) -> None:
        assert detect_xss('<a onclick="evil()">click me</a>') is True

    def test_iframe_tag_detected(self) -> None:
        assert detect_xss('<iframe src="http://evil.com"></iframe>') is True

    def test_embed_tag_detected(self) -> None:
        assert detect_xss("<embed src='exploit.swf'>") is True

    def test_css_expression_detected(self) -> None:
        assert detect_xss("background: expression(alert(1))") is True

    def test_html_encoded_lt_numeric_detected(self) -> None:
        assert detect_xss("&#60;script>alert(1)</script>") is True

    def test_html_encoded_lt_hex_detected(self) -> None:
        assert detect_xss("&#x3C;script>alert(1)</script>") is True

    def test_empty_string_returns_false(self) -> None:
        assert detect_xss("") is False

    def test_markdown_content_is_clean(self) -> None:
        md = "# Heading\n\n- Item 1\n- Item 2\n\n**Bold** and _italic_."
        assert detect_xss(md) is False

    def test_sql_keywords_alone_do_not_trigger_xss(self) -> None:
        assert detect_xss("SELECT * FROM users") is False

    def test_form_tag_detected(self) -> None:
        assert detect_xss('<form action="/submit">') is True

    def test_object_tag_detected(self) -> None:
        assert detect_xss('<object data="exploit.swf">') is True


# ---------------------------------------------------------------------------
# detect_sql_injection
# ---------------------------------------------------------------------------


class TestDetectSqlInjection:
    """Tests for detect_sql_injection(value) -> bool."""

    def test_clean_text_returns_false(self) -> None:
        assert detect_sql_injection("My favourite colour is blue.") is False

    def test_empty_string_returns_false(self) -> None:
        assert detect_sql_injection("") is False

    def test_union_select_detected(self) -> None:
        assert (
            detect_sql_injection("' UNION SELECT username, password FROM users--")
            is True
        )

    def test_or_tautology_numeric_detected(self) -> None:
        assert detect_sql_injection("' OR 1=1 --") is True

    def test_drop_table_after_semicolon_detected(self) -> None:
        assert detect_sql_injection("foo'; DROP TABLE users;--") is True

    def test_delete_after_semicolon_detected(self) -> None:
        assert detect_sql_injection("id=1; DELETE FROM sessions") is True

    def test_sleep_blind_injection_detected(self) -> None:
        assert detect_sql_injection("1'; SLEEP(5)--") is True

    def test_waitfor_delay_detected(self) -> None:
        assert detect_sql_injection("1'; WAITFOR DELAY '0:0:5'--") is True

    def test_benchmark_detected(self) -> None:
        assert detect_sql_injection("1 AND BENCHMARK(1000000, MD5(1))") is True

    def test_xp_cmdshell_detected(self) -> None:
        assert detect_sql_injection("1; EXEC xp_cmdshell('dir')--") is True

    def test_extractvalue_detected(self) -> None:
        assert (
            detect_sql_injection(
                "1 AND EXTRACTVALUE(1, concat(0x7e, (SELECT version())))"
            )
            is True
        )

    def test_null_byte_detected(self) -> None:
        assert detect_sql_injection("user\x00admin") is True

    def test_or_string_tautology_detected(self) -> None:
        assert detect_sql_injection("' OR 'x'='x") is True

    def test_comment_after_quote_detected(self) -> None:
        assert detect_sql_injection("admin'--") is True

    def test_normal_apostrophe_in_name_is_clean(self) -> None:
        # A plain apostrophe in a name should NOT be flagged as SQLi
        # (our patterns require specific SQL constructs, not just any apostrophe)
        assert detect_sql_injection("O'Brien") is False

    def test_select_keyword_alone_is_clean(self) -> None:
        # Lone SQL keywords in a sentence should not trigger (pattern requires context)
        assert detect_sql_injection("I want to select a good restaurant") is False

    def test_boolean_injection_detected(self) -> None:
        assert detect_sql_injection("' AND '1'='1") is True


# ---------------------------------------------------------------------------
# sanitize_string
# ---------------------------------------------------------------------------


class TestSanitizeString:
    """Tests for sanitize_string(value) -> str."""

    def test_clean_string_returned_unchanged(self) -> None:
        value = "Hello, my name is Alice!"
        result = sanitize_string(value)
        assert result == value

    def test_empty_string_returned_unchanged(self) -> None:
        assert sanitize_string("") == ""

    def test_html_tags_stripped(self) -> None:
        result = sanitize_string("Hello <b>world</b>!")
        assert "<b>" not in result
        assert "world" in result
        assert "Hello" in result

    def test_html_entity_decoded_after_stripping(self) -> None:
        # &amp; should be decoded to & after tag stripping
        result = sanitize_string("Fish &amp; chips")
        assert "&amp;" not in result
        assert "&" in result

    def test_xss_script_tag_raises_value_error(self) -> None:
        with pytest.raises(ValueError, match="XSS"):
            sanitize_string('<script>alert("xss")</script>')

    def test_xss_javascript_protocol_raises_value_error(self) -> None:
        with pytest.raises(ValueError, match="XSS"):
            sanitize_string("javascript:void(0)")

    def test_sql_injection_raises_value_error(self) -> None:
        with pytest.raises(ValueError, match="SQL injection"):
            sanitize_string("' UNION SELECT * FROM users--")

    def test_sql_drop_table_raises_value_error(self) -> None:
        with pytest.raises(ValueError, match="SQL injection"):
            sanitize_string("x'; DROP TABLE users;--")

    def test_unicode_nfc_normalised(self) -> None:
        # Compose a character from combining marks and check it's normalised

        # NFC normalised form of 'é' — precomposed vs decomposed
        precomposed = "\u00e9"  # é (single code point)
        decomposed = "e\u0301"  # e + combining acute accent (two code points)
        result = sanitize_string(decomposed)
        # After NFC normalisation the decomposed form should equal the precomposed form
        assert result == precomposed

    def test_regular_apostrophe_in_name_is_safe(self) -> None:
        # O'Brien has an apostrophe but is not a SQL injection pattern
        result = sanitize_string("O'Brien")
        assert result == "O'Brien"

    def test_markdown_text_is_safe(self) -> None:
        md = "# My Note\n\nSome **bold** and _italic_ text.\n\n- Item 1"
        result = sanitize_string(md)
        assert "bold" in result
        assert "italic" in result


# ---------------------------------------------------------------------------
# sanitize_user_string (Pydantic field_validator compatible)
# ---------------------------------------------------------------------------


class TestSanitizeUserString:
    """Tests for the sanitize_user_string(cls, value) helper."""

    def test_clean_string_passes_through(self) -> None:
        result = sanitize_user_string(None, "Normal text")
        assert result == "Normal text"

    def test_non_string_value_passed_through_unchanged(self) -> None:
        # Non-string values should be returned as-is (type checking is Pydantic's job)
        assert sanitize_user_string(None, 123) == 123  # type: ignore[arg-type]

    def test_xss_raises_value_error(self) -> None:
        with pytest.raises(ValueError):
            sanitize_user_string(None, "<script>bad()</script>")

    def test_sql_injection_raises_value_error(self) -> None:
        with pytest.raises(ValueError):
            sanitize_user_string(None, "' OR 1=1 --")


# ---------------------------------------------------------------------------
# Schema integration tests
# ---------------------------------------------------------------------------


class TestRegisterRequestValidation:
    """Verify RegisterRequest enforces sanitization on display_name."""

    def test_valid_payload_accepted(self) -> None:
        from app.schemas.auth import RegisterRequest

        req = RegisterRequest(
            email="user@example.com",
            password="Str0ng!Password123",
            display_name="Jane Doe",
        )
        assert req.display_name == "Jane Doe"

    def test_xss_in_display_name_rejected(self) -> None:
        from app.schemas.auth import RegisterRequest

        with pytest.raises(ValidationError) as exc_info:
            RegisterRequest(
                email="user@example.com",
                password="Str0ng!Password123",
                display_name='<script>alert("xss")</script>',
            )
        assert (
            "XSS" in str(exc_info.value) or "cross-site" in str(exc_info.value).lower()
        )

    def test_sql_injection_in_display_name_rejected(self) -> None:
        from app.schemas.auth import RegisterRequest

        with pytest.raises(ValidationError):
            RegisterRequest(
                email="user@example.com",
                password="Str0ng!Password123",
                display_name="' UNION SELECT * FROM users--",
            )

    def test_password_max_length_enforced(self) -> None:
        from app.schemas.auth import RegisterRequest

        with pytest.raises(ValidationError):
            RegisterRequest(
                email="user@example.com",
                password="A" * 129,  # exceeds max_length=128
                display_name="Test",
            )


class TestConversationCreateValidation:
    """Verify ConversationCreate applies sanitization to title and provider."""

    def test_valid_payload_accepted(self) -> None:
        from app.schemas.conversations import ConversationCreate

        req = ConversationCreate(title="My Conversation", provider="openai")
        assert req.title == "My Conversation"
        assert req.provider == "openai"

    def test_xss_in_title_rejected(self) -> None:
        from app.schemas.conversations import ConversationCreate

        with pytest.raises(ValidationError):
            ConversationCreate(title="<script>alert(1)</script>", provider="openai")

    def test_title_max_length_enforced(self) -> None:
        from app.schemas.conversations import ConversationCreate

        with pytest.raises(ValidationError):
            ConversationCreate(title="x" * 501, provider="openai")


class TestTodoCreateValidation:
    """Verify TodoCreate sanitizes title, description, and tags."""

    def test_valid_todo_accepted(self) -> None:
        from app.schemas.productivity import TodoCreate

        todo = TodoCreate(
            title="Buy groceries", description="Milk and eggs", tags=["personal"]
        )
        assert todo.title == "Buy groceries"

    def test_xss_in_title_rejected(self) -> None:
        from app.schemas.productivity import TodoCreate

        with pytest.raises(ValidationError):
            TodoCreate(title="<script>evil()</script>")

    def test_sql_injection_in_description_rejected(self) -> None:
        from app.schemas.productivity import TodoCreate

        with pytest.raises(ValidationError):
            TodoCreate(title="Task", description="' UNION SELECT * FROM users--")

    def test_invalid_priority_rejected(self) -> None:
        from app.schemas.productivity import TodoCreate

        with pytest.raises(ValidationError):
            TodoCreate(title="Task", priority="critical")

    def test_xss_in_tag_rejected(self) -> None:
        from app.schemas.productivity import TodoCreate

        with pytest.raises(ValidationError):
            TodoCreate(title="Task", tags=["<script>evil()</script>"])

    def test_too_many_tags_rejected(self) -> None:
        from app.schemas.productivity import TodoCreate

        with pytest.raises(ValidationError):
            TodoCreate(title="Task", tags=["tag"] * 51)


class TestDocumentQueryRequestValidation:
    """Verify DocumentQueryRequest sanitizes the query field."""

    def test_valid_query_accepted(self) -> None:
        from app.schemas.rag import DocumentQueryRequest

        req = DocumentQueryRequest(query="What are the key findings?")
        assert req.query == "What are the key findings?"

    def test_xss_in_query_rejected(self) -> None:
        from app.schemas.rag import DocumentQueryRequest

        with pytest.raises(ValidationError):
            DocumentQueryRequest(query='<script>alert("xss")</script>')

    def test_query_max_length_enforced(self) -> None:
        from app.schemas.rag import DocumentQueryRequest

        with pytest.raises(ValidationError):
            DocumentQueryRequest(query="x" * 2001)


class TestHabitCreateValidation:
    """Verify HabitCreate sanitizes name and description, validates recurrence."""

    def test_valid_habit_accepted(self) -> None:
        from app.schemas.productivity import HabitCreate

        habit = HabitCreate(name="Morning run", description="Run 5km every morning")
        assert habit.name == "Morning run"

    def test_xss_in_name_rejected(self) -> None:
        from app.schemas.productivity import HabitCreate

        with pytest.raises(ValidationError):
            HabitCreate(name="<script>evil()</script>")

    def test_invalid_recurrence_rejected(self) -> None:
        from app.schemas.productivity import HabitCreate

        with pytest.raises(ValidationError):
            HabitCreate(name="Habit", recurrence="hourly")

    def test_target_frequency_bounds_enforced(self) -> None:
        from app.schemas.productivity import HabitCreate

        with pytest.raises(ValidationError):
            HabitCreate(name="Habit", target_frequency=0)

        with pytest.raises(ValidationError):
            HabitCreate(name="Habit", target_frequency=366)


class TestReminderCreateValidation:
    """Verify ReminderCreate sanitizes title and recurrence_rule."""

    def test_valid_reminder_accepted(self) -> None:
        from datetime import datetime, timezone

        from app.schemas.productivity import ReminderCreate

        reminder = ReminderCreate(
            title="Team meeting",
            trigger_time=datetime(2025, 1, 15, 10, 0, tzinfo=timezone.utc),
        )
        assert reminder.title == "Team meeting"

    def test_xss_in_title_rejected(self) -> None:
        from datetime import datetime, timezone

        from app.schemas.productivity import ReminderCreate

        with pytest.raises(ValidationError):
            ReminderCreate(
                title='<img onerror="evil()">',
                trigger_time=datetime(2025, 1, 15, 10, 0, tzinfo=timezone.utc),
            )

    def test_sql_injection_in_recurrence_rule_rejected(self) -> None:
        from datetime import datetime, timezone

        from app.schemas.productivity import ReminderCreate

        with pytest.raises(ValidationError):
            ReminderCreate(
                title="Meeting",
                trigger_time=datetime(2025, 1, 15, 10, 0, tzinfo=timezone.utc),
                recurrence_rule="FREQ=DAILY; DROP TABLE reminders;--",
            )


class TestCalendarEventCreateValidation:
    """Verify CalendarEventCreate sanitizes title, description, and location."""

    def test_valid_event_accepted(self) -> None:
        from datetime import datetime, timezone

        from app.schemas.productivity import CalendarEventCreate

        event = CalendarEventCreate(
            title="Sprint Review",
            description="End of sprint demo",
            start_time=datetime(2025, 1, 20, 9, 0, tzinfo=timezone.utc),
            end_time=datetime(2025, 1, 20, 10, 0, tzinfo=timezone.utc),
            location="Conference Room A",
        )
        assert event.title == "Sprint Review"

    def test_xss_in_description_rejected(self) -> None:
        from datetime import datetime, timezone

        from app.schemas.productivity import CalendarEventCreate

        with pytest.raises(ValidationError):
            CalendarEventCreate(
                title="Meeting",
                description="<script>steal_tokens()</script>",
                start_time=datetime(2025, 1, 20, 9, 0, tzinfo=timezone.utc),
                end_time=datetime(2025, 1, 20, 10, 0, tzinfo=timezone.utc),
            )

    def test_invalid_source_rejected(self) -> None:
        from datetime import datetime, timezone

        from app.schemas.productivity import CalendarEventCreate

        with pytest.raises(ValidationError):
            CalendarEventCreate(
                title="Meeting",
                start_time=datetime(2025, 1, 20, 9, 0, tzinfo=timezone.utc),
                end_time=datetime(2025, 1, 20, 10, 0, tzinfo=timezone.utc),
                source="evil_source",
            )


class TestAdminUserUpdateValidation:
    """Verify UserUpdateRequest only accepts whitelisted actions."""

    def test_valid_action_accepted(self) -> None:
        from app.schemas.admin import UserUpdateRequest

        for action in (
            "promote",
            "demote",
            "make_admin",
            "remove_admin",
            "deactivate",
            "reactivate",
        ):
            req = UserUpdateRequest(action=action)
            assert req.action == action

    def test_arbitrary_string_rejected(self) -> None:
        from app.schemas.admin import UserUpdateRequest

        with pytest.raises(ValidationError):
            UserUpdateRequest(action="drop_all_tables")

    def test_xss_in_action_rejected(self) -> None:
        from app.schemas.admin import UserUpdateRequest

        with pytest.raises(ValidationError):
            UserUpdateRequest(action="<script>evil()</script>")
