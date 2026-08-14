"""Unit tests for app.workers.metrics.

Tests cover:
- record_token_usage: increments all three LLM counters with correct provider label
- setup_celery_metrics: connects all three Celery signals
- _on_task_failure: increments celery_failed_tasks_total
- _on_task_success: increments celery_completed_tasks_total

Each test uses a fresh CollectorRegistry to avoid cross-test counter
pollution (prometheus_client counters are global by default; creating them
on isolated registries prevents "duplicate metric" errors between test runs).
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

from prometheus_client import CollectorRegistry, Counter

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_counter(
    name: str, labelnames: list[str], registry: CollectorRegistry
) -> Counter:
    """Create a fresh Counter on *registry* for use in isolated tests."""
    return Counter(name, "test counter", labelnames=labelnames, registry=registry)


# ---------------------------------------------------------------------------
# record_token_usage
# ---------------------------------------------------------------------------


class TestRecordTokenUsage:
    """Tests for the record_token_usage helper function."""

    def test_increments_cost_counter(self) -> None:
        """record_token_usage increments llm_token_cost_usd_total by cost_usd."""
        registry = CollectorRegistry()
        cost_ctr = _make_counter("llm_token_cost_usd_total", ["provider"], registry)
        input_ctr = _make_counter("llm_input_tokens_total", ["provider"], registry)
        output_ctr = _make_counter("llm_output_tokens_total", ["provider"], registry)

        with (
            patch("app.workers.metrics.llm_token_cost_usd_total", cost_ctr),
            patch("app.workers.metrics.llm_input_tokens_total", input_ctr),
            patch("app.workers.metrics.llm_output_tokens_total", output_ctr),
        ):
            from app.workers.metrics import record_token_usage

            record_token_usage(
                provider="openai",
                input_tokens=100,
                output_tokens=50,
                cost_usd=0.0025,
            )

        value = cost_ctr.labels(provider="openai")._value.get()
        assert abs(value - 0.0025) < 1e-9, f"Expected ~0.0025, got {value}"

    def test_increments_input_tokens_counter(self) -> None:
        """record_token_usage increments llm_input_tokens_total by input_tokens."""
        registry = CollectorRegistry()
        cost_ctr = _make_counter("llm_token_cost_usd_total", ["provider"], registry)
        input_ctr = _make_counter("llm_input_tokens_total", ["provider"], registry)
        output_ctr = _make_counter("llm_output_tokens_total", ["provider"], registry)

        with (
            patch("app.workers.metrics.llm_token_cost_usd_total", cost_ctr),
            patch("app.workers.metrics.llm_input_tokens_total", input_ctr),
            patch("app.workers.metrics.llm_output_tokens_total", output_ctr),
        ):
            from app.workers.metrics import record_token_usage

            record_token_usage(
                provider="gemini",
                input_tokens=200,
                output_tokens=75,
                cost_usd=0.001,
            )

        value = input_ctr.labels(provider="gemini")._value.get()
        assert value == 200.0, f"Expected 200, got {value}"

    def test_increments_output_tokens_counter(self) -> None:
        """record_token_usage increments llm_output_tokens_total by output_tokens."""
        registry = CollectorRegistry()
        cost_ctr = _make_counter("llm_token_cost_usd_total", ["provider"], registry)
        input_ctr = _make_counter("llm_input_tokens_total", ["provider"], registry)
        output_ctr = _make_counter("llm_output_tokens_total", ["provider"], registry)

        with (
            patch("app.workers.metrics.llm_token_cost_usd_total", cost_ctr),
            patch("app.workers.metrics.llm_input_tokens_total", input_ctr),
            patch("app.workers.metrics.llm_output_tokens_total", output_ctr),
        ):
            from app.workers.metrics import record_token_usage

            record_token_usage(
                provider="claude",
                input_tokens=300,
                output_tokens=120,
                cost_usd=0.003,
            )

        value = output_ctr.labels(provider="claude")._value.get()
        assert value == 120.0, f"Expected 120, got {value}"

    def test_uses_correct_provider_label(self) -> None:
        """record_token_usage stores values under the correct provider label."""
        registry = CollectorRegistry()
        cost_ctr = _make_counter("llm_token_cost_usd_total", ["provider"], registry)
        input_ctr = _make_counter("llm_input_tokens_total", ["provider"], registry)
        output_ctr = _make_counter("llm_output_tokens_total", ["provider"], registry)

        with (
            patch("app.workers.metrics.llm_token_cost_usd_total", cost_ctr),
            patch("app.workers.metrics.llm_input_tokens_total", input_ctr),
            patch("app.workers.metrics.llm_output_tokens_total", output_ctr),
        ):
            from app.workers.metrics import record_token_usage

            record_token_usage("openai", 10, 5, 0.001)
            record_token_usage("gemini", 20, 10, 0.002)

        # openai label should only have its own values
        assert input_ctr.labels(provider="openai")._value.get() == 10.0
        assert input_ctr.labels(provider="gemini")._value.get() == 20.0

    def test_all_three_counters_incremented_together(self) -> None:
        """A single call to record_token_usage increments all three counters."""
        registry = CollectorRegistry()
        cost_ctr = _make_counter("llm_token_cost_usd_total", ["provider"], registry)
        input_ctr = _make_counter("llm_input_tokens_total", ["provider"], registry)
        output_ctr = _make_counter("llm_output_tokens_total", ["provider"], registry)

        with (
            patch("app.workers.metrics.llm_token_cost_usd_total", cost_ctr),
            patch("app.workers.metrics.llm_input_tokens_total", input_ctr),
            patch("app.workers.metrics.llm_output_tokens_total", output_ctr),
        ):
            from app.workers.metrics import record_token_usage

            record_token_usage("mistral", 50, 25, 0.0005)

        assert input_ctr.labels(provider="mistral")._value.get() == 50.0
        assert output_ctr.labels(provider="mistral")._value.get() == 25.0
        assert abs(cost_ctr.labels(provider="mistral")._value.get() - 0.0005) < 1e-9


# ---------------------------------------------------------------------------
# setup_celery_metrics
# ---------------------------------------------------------------------------


class TestSetupCeleryMetrics:
    """Tests for setup_celery_metrics signal wiring."""

    def _make_celery_signals_mock(self):
        """Return a mock that looks like celery.signals with connect-able attributes."""
        signals_mod = MagicMock()
        signals_mod.task_failure = MagicMock()
        signals_mod.task_success = MagicMock()
        signals_mod.task_retry = MagicMock()
        return signals_mod

    def test_connects_task_failure_signal(self) -> None:
        """setup_celery_metrics connects _on_task_failure to task_failure signal."""

        signals_mock = self._make_celery_signals_mock()
        celery_mock = MagicMock()
        celery_mock.signals = signals_mock

        with patch.dict(
            "sys.modules", {"celery": celery_mock, "celery.signals": signals_mock}
        ):
            import app.workers.metrics as metrics_mod

            mock_app = MagicMock()
            metrics_mod.setup_celery_metrics(mock_app)

            signals_mock.task_failure.connect.assert_called_once_with(
                metrics_mod._on_task_failure, weak=False
            )

    def test_connects_all_three_signals(self) -> None:
        """setup_celery_metrics connects exactly three signal handlers."""
        signals_mock = self._make_celery_signals_mock()
        celery_mock = MagicMock()

        with patch.dict(
            "sys.modules", {"celery": celery_mock, "celery.signals": signals_mock}
        ):
            import app.workers.metrics as metrics_mod

            mock_app = MagicMock()
            metrics_mod.setup_celery_metrics(mock_app)

            assert signals_mock.task_failure.connect.call_count == 1
            assert signals_mock.task_success.connect.call_count == 1
            assert signals_mock.task_retry.connect.call_count == 1


# ---------------------------------------------------------------------------
# _on_task_failure
# ---------------------------------------------------------------------------


class TestOnTaskFailure:
    """Tests for the _on_task_failure signal handler."""

    def test_increments_failed_counter_with_task_name(self) -> None:
        """_on_task_failure increments celery_failed_tasks_total with sender task name."""
        registry = CollectorRegistry()
        failed_ctr = _make_counter("celery_failed_tasks_total", ["task_name"], registry)

        with patch("app.workers.metrics.celery_failed_tasks_total", failed_ctr):
            from app.workers.metrics import _on_task_failure

            mock_sender = MagicMock()
            mock_sender.name = "app.workers.tasks.process_document"

            _on_task_failure(sender=mock_sender, task_id="abc-123", exception=None)

        value = failed_ctr.labels(
            task_name="app.workers.tasks.process_document"
        )._value.get()
        assert value == 1.0, f"Expected 1.0, got {value}"

    def test_uses_sender_name_attribute(self) -> None:
        """_on_task_failure reads the task name from sender.name attribute."""
        registry = CollectorRegistry()
        failed_ctr = _make_counter("celery_failed_tasks_total", ["task_name"], registry)

        with patch("app.workers.metrics.celery_failed_tasks_total", failed_ctr):
            from app.workers.metrics import _on_task_failure

            sender = MagicMock()
            sender.name = "my.custom.task"

            _on_task_failure(sender=sender)

        assert failed_ctr.labels(task_name="my.custom.task")._value.get() == 1.0

    def test_handles_none_sender_gracefully(self) -> None:
        """_on_task_failure uses 'unknown' when sender is None."""
        registry = CollectorRegistry()
        failed_ctr = _make_counter("celery_failed_tasks_total", ["task_name"], registry)

        with patch("app.workers.metrics.celery_failed_tasks_total", failed_ctr):
            from app.workers.metrics import _on_task_failure

            # Should not raise
            _on_task_failure(sender=None)

        assert failed_ctr.labels(task_name="unknown")._value.get() == 1.0

    def test_increments_by_one_per_call(self) -> None:
        """_on_task_failure increments the counter by exactly 1 per call."""
        registry = CollectorRegistry()
        failed_ctr = _make_counter("celery_failed_tasks_total", ["task_name"], registry)

        with patch("app.workers.metrics.celery_failed_tasks_total", failed_ctr):
            from app.workers.metrics import _on_task_failure

            sender = MagicMock()
            sender.name = "repeat.task"

            _on_task_failure(sender=sender)
            _on_task_failure(sender=sender)
            _on_task_failure(sender=sender)

        assert failed_ctr.labels(task_name="repeat.task")._value.get() == 3.0


# ---------------------------------------------------------------------------
# _on_task_success
# ---------------------------------------------------------------------------


class TestOnTaskSuccess:
    """Tests for the _on_task_success signal handler."""

    def test_increments_completed_counter_with_task_name(self) -> None:
        """_on_task_success increments celery_completed_tasks_total with sender task name."""
        registry = CollectorRegistry()
        completed_ctr = _make_counter(
            "celery_completed_tasks_total", ["task_name"], registry
        )

        with patch("app.workers.metrics.celery_completed_tasks_total", completed_ctr):
            from app.workers.metrics import _on_task_success

            mock_sender = MagicMock()
            mock_sender.name = "app.workers.tasks.send_notification"

            _on_task_success(sender=mock_sender, result="ok")

        value = completed_ctr.labels(
            task_name="app.workers.tasks.send_notification"
        )._value.get()
        assert value == 1.0, f"Expected 1.0, got {value}"

    def test_uses_sender_name_attribute(self) -> None:
        """_on_task_success reads task name from sender.name."""
        registry = CollectorRegistry()
        completed_ctr = _make_counter(
            "celery_completed_tasks_total", ["task_name"], registry
        )

        with patch("app.workers.metrics.celery_completed_tasks_total", completed_ctr):
            from app.workers.metrics import _on_task_success

            sender = MagicMock()
            sender.name = "another.task"

            _on_task_success(sender=sender)

        assert completed_ctr.labels(task_name="another.task")._value.get() == 1.0

    def test_handles_none_sender_gracefully(self) -> None:
        """_on_task_success uses 'unknown' when sender is None."""
        registry = CollectorRegistry()
        completed_ctr = _make_counter(
            "celery_completed_tasks_total", ["task_name"], registry
        )

        with patch("app.workers.metrics.celery_completed_tasks_total", completed_ctr):
            from app.workers.metrics import _on_task_success

            _on_task_success(sender=None)

        assert completed_ctr.labels(task_name="unknown")._value.get() == 1.0

    def test_increments_by_one_per_call(self) -> None:
        """_on_task_success increments the counter by exactly 1 per call."""
        registry = CollectorRegistry()
        completed_ctr = _make_counter(
            "celery_completed_tasks_total", ["task_name"], registry
        )

        with patch("app.workers.metrics.celery_completed_tasks_total", completed_ctr):
            from app.workers.metrics import _on_task_success

            sender = MagicMock()
            sender.name = "repeated.success.task"

            _on_task_success(sender=sender)
            _on_task_success(sender=sender)

        assert (
            completed_ctr.labels(task_name="repeated.success.task")._value.get() == 2.0
        )
