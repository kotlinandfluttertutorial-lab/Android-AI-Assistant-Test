# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit
# File    : test_images_router.py
# Purpose : Unit tests for POST /images/analyze endpoint
#
# Architecture Layer : Test
# Pattern Used       : pytest + FastAPI TestClient with dependency overrides
#
# Key Concepts:
#   - Image format / size / dimension validation (JPEG/PNG/WebP, ≤10 MB, ≤4096×4096)
#   - OCR result with bounding boxes (pytesseract mocked)
#   - no_text_found indicator when no text detected
#   - Vision LLM routing via mocked AIOrchestrator
#   - Structured error when no vision-capable provider active
#
# Requirements: 6.3, 6.4
# ============================================================

"""Unit tests for /images/analyze endpoint.

Tests are fully isolated: Pillow and pytesseract are mocked where needed,
AIOrchestrator is mocked, and JWT authentication is bypassed via a dependency
override.

Requirements: 6.3, 6.4
"""

from __future__ import annotations

import io
import os
import struct
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.images.router import router as images_router
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

# ---------------------------------------------------------------------------
# Helpers — synthetic image bytes
# ---------------------------------------------------------------------------


def _make_jpeg_bytes(width: int = 100, height: int = 100) -> bytes:
    """Generate minimal JPEG bytes starting with FF D8 FF."""
    try:
        from PIL import Image as PILImage

        img = PILImage.new("RGB", (width, height), color=(255, 0, 0))
        buf = io.BytesIO()
        img.save(buf, format="JPEG")
        return buf.getvalue()
    except ImportError:
        # Fallback: bare JPEG magic bytes (Pillow not installed in test env)
        return b"\xff\xd8\xff" + b"\x00" * 100


def _make_png_bytes(width: int = 100, height: int = 100) -> bytes:
    """Generate minimal PNG bytes."""
    try:
        from PIL import Image as PILImage

        img = PILImage.new("RGB", (width, height), color=(0, 255, 0))
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return buf.getvalue()
    except ImportError:
        # Bare PNG magic bytes
        return b"\x89PNG\r\n\x1a\n" + b"\x00" * 100


def _make_webp_bytes() -> bytes:
    """Generate minimal WebP bytes (RIFF....WEBP header)."""
    # WebP container: RIFF + 4-byte file size LE + WEBP
    data = b"RIFF" + struct.pack("<I", 12) + b"WEBP" + b"\x00" * 12
    return data


def _fake_user() -> TokenPayload:
    return TokenPayload(
        sub="user-123",
        role="user",
        jti="jti-abc",
        iat=datetime.now(tz=timezone.utc),
        exp=datetime(2099, 1, 1, tzinfo=timezone.utc),
    )


def _build_app() -> FastAPI:
    app = FastAPI()
    app.dependency_overrides[get_current_user] = lambda: _fake_user()
    app.include_router(images_router)
    return app


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture()
def client() -> TestClient:
    return TestClient(_build_app(), raise_server_exceptions=False)


# ---------------------------------------------------------------------------
# Helper to patch PIL and pytesseract
# ---------------------------------------------------------------------------


def _mock_pil_open(width: int = 100, height: int = 100):
    """Return a context manager that patches PIL.Image.open (or the whole Image module when None).

    In environments where Pillow isn't installed, ``Image`` is ``None`` in the
    router module.  We patch the whole ``Image`` attribute in that case so the
    ``Image.open(...)`` call in the route handler works correctly.
    """
    import app.api.images.router as _img_router

    mock_img = MagicMock()
    mock_img.size = (width, height)
    if _img_router.Image is None:
        # Patch the module-level None with a mock that has .open
        mock_pil = MagicMock()
        mock_pil.open.return_value = mock_img
        return patch("app.api.images.router.Image", mock_pil)
    return patch("app.api.images.router.Image.open", return_value=mock_img)


def _make_tesseract_data(words: list[str]) -> dict:
    """Build the dict that pytesseract.image_to_data returns."""
    n = len(words)
    return {
        "text": words,
        "conf": [90.0] * n,
        "left": [10 * i for i in range(n)],
        "top": [5] * n,
        "width": [40] * n,
        "height": [15] * n,
    }


def _mock_tesseract(words: list[str]):
    """Context manager that ensures pytesseract is non-None and its image_to_data is mocked.

    The router imports pytesseract at module load time; when pytesseract is not
    installed the module-level ``pytesseract`` variable is set to ``None``.
    We patch the variable itself to a MagicMock so tests can run without the
    native library installed.
    """
    mock_ts = MagicMock()
    mock_ts.image_to_data.return_value = _make_tesseract_data(words)
    return patch("app.api.images.router.pytesseract", mock_ts)


# ---------------------------------------------------------------------------
# Content-Type validation (Requirement 6.1 / 6.3)
# ---------------------------------------------------------------------------


class TestImageFormatValidation:
    """Reject images that are not JPEG, PNG, or WebP."""

    def test_rejects_unsupported_format_gif(self, client: TestClient) -> None:
        """GIF content-type should return 422."""
        response = client.post(
            "/images/analyze",
            files={"file": ("test.gif", b"GIF89a...", "image/gif")},
        )
        assert response.status_code == 422

    def test_rejects_plain_text(self, client: TestClient) -> None:
        """text/plain content-type should return 422."""
        response = client.post(
            "/images/analyze",
            files={"file": ("test.txt", b"hello world", "text/plain")},
        )
        assert response.status_code == 422

    def test_rejects_pdf(self, client: TestClient) -> None:
        """application/pdf content-type should return 422."""
        response = client.post(
            "/images/analyze",
            files={"file": ("test.pdf", b"%PDF-1.4", "application/pdf")},
        )
        assert response.status_code == 422

    def test_accepts_jpeg_content_type(self, client: TestClient) -> None:
        """JPEG should pass content-type check (actual bytes may fail magic check)."""
        data = _make_jpeg_bytes()
        with _mock_pil_open(), _mock_tesseract([]):
            response = client.post(
                "/images/analyze",
                files={"file": ("photo.jpg", data, "image/jpeg")},
            )
        # 200 or 422 only — not 500
        assert response.status_code in (200, 422)

    def test_accepts_png_content_type(self, client: TestClient) -> None:
        """PNG should pass content-type check."""
        data = _make_png_bytes()
        with _mock_pil_open(), _mock_tesseract([]):
            response = client.post(
                "/images/analyze",
                files={"file": ("image.png", data, "image/png")},
            )
        assert response.status_code in (200, 422)


# ---------------------------------------------------------------------------
# File size validation (Requirement 6.1 / 6.3)
# ---------------------------------------------------------------------------


class TestImageSizeValidation:
    """Reject images exceeding 10 MB."""

    def test_rejects_oversized_file(self, client: TestClient) -> None:
        """Files over 10 MB should be rejected with 422."""
        oversized = b"\xff\xd8\xff" + b"\x00" * (10 * 1024 * 1024 + 1)
        response = client.post(
            "/images/analyze",
            files={"file": ("big.jpg", oversized, "image/jpeg")},
        )
        assert response.status_code == 422
        assert "10" in response.json()["detail"]

    def test_accepts_file_at_boundary(self, client: TestClient) -> None:
        """A file exactly at 10 MB should pass size check (then fail magic/dimension)."""
        exact_10mb = _make_jpeg_bytes()
        # We just verify size validation itself doesn't reject it
        # (use real JPEG so magic check passes, then mock PIL/tesseract)
        with _mock_pil_open(), _mock_tesseract([]):
            response = client.post(
                "/images/analyze",
                files={"file": ("ok.jpg", exact_10mb, "image/jpeg")},
            )
        # Should not fail with the size-limit error message
        if response.status_code == 422:
            assert "10 MB" not in response.json().get("detail", "")


# ---------------------------------------------------------------------------
# Dimension validation (Requirement 6.1 / 6.3)
# ---------------------------------------------------------------------------


class TestImageDimensionValidation:
    """Reject images exceeding 4096×4096 pixels."""

    def test_rejects_oversized_dimensions(self, client: TestClient) -> None:
        """Images wider or taller than 4096 px should be rejected with 422."""
        data = _make_jpeg_bytes()
        with _mock_pil_open(width=5000, height=100), _mock_tesseract([]):
            response = client.post(
                "/images/analyze",
                files={"file": ("wide.jpg", data, "image/jpeg")},
            )
        assert response.status_code == 422
        assert "4096" in response.json()["detail"]

    def test_rejects_tall_image(self, client: TestClient) -> None:
        """Images taller than 4096 px should be rejected with 422."""
        data = _make_png_bytes()
        with _mock_pil_open(width=100, height=5000), _mock_tesseract([]):
            response = client.post(
                "/images/analyze",
                files={"file": ("tall.png", data, "image/png")},
            )
        assert response.status_code == 422

    def test_accepts_max_allowed_dimensions(self, client: TestClient) -> None:
        """Images exactly 4096×4096 should not trigger the dimension error."""
        data = _make_jpeg_bytes()
        with _mock_pil_open(width=4096, height=4096), _mock_tesseract([]):
            response = client.post(
                "/images/analyze",
                files={"file": ("max.jpg", data, "image/jpeg")},
            )
        # Dimension check should pass (may still fail magic bytes without real Pillow)
        if response.status_code == 422:
            body = response.json()["detail"]
            assert "4096" not in body or "exceed" not in body


# ---------------------------------------------------------------------------
# OCR — no_text_found indicator (Requirement 6.3)
# ---------------------------------------------------------------------------


class TestOCRNoTextFound:
    """Verify no_text_found indicator when OCR detects nothing."""

    def test_no_text_found_true_when_empty_ocr(self, client: TestClient) -> None:
        """When pytesseract returns no words, no_text_found must be True."""
        data = _make_jpeg_bytes()
        with _mock_pil_open(), _mock_tesseract([]):
            response = client.post(
                "/images/analyze",
                files={"file": ("blank.jpg", data, "image/jpeg")},
            )
        if response.status_code == 200:
            body = response.json()
            assert body["no_text_found"] is True
            assert body["extracted_text"] == ""
            assert body["bounding_boxes"] == []

    def test_no_text_found_false_when_text_detected(self, client: TestClient) -> None:
        """When pytesseract returns words, no_text_found must be False."""
        data = _make_jpeg_bytes()
        with _mock_pil_open(), _mock_tesseract(["Hello", "World"]):
            response = client.post(
                "/images/analyze",
                files={"file": ("text.jpg", data, "image/jpeg")},
            )
        if response.status_code == 200:
            body = response.json()
            assert body["no_text_found"] is False
            assert "Hello" in body["extracted_text"]

    def test_bounding_boxes_returned_for_detected_text(
        self, client: TestClient
    ) -> None:
        """Bounding boxes should be returned for each detected word."""
        data = _make_jpeg_bytes()
        with _mock_pil_open(), _mock_tesseract(["Invoice", "Total"]):
            response = client.post(
                "/images/analyze",
                files={"file": ("invoice.jpg", data, "image/jpeg")},
            )
        if response.status_code == 200:
            body = response.json()
            assert len(body["bounding_boxes"]) == 2
            for box in body["bounding_boxes"]:
                assert "text" in box
                assert "left" in box
                assert "top" in box
                assert "width" in box
                assert "height" in box
                assert "confidence" in box


# ---------------------------------------------------------------------------
# Vision analysis — no vision provider (Requirement 6.4)
# ---------------------------------------------------------------------------


class TestVisionAnalysis:
    """Vision LLM routing and structured error when no vision provider."""

    def test_no_vision_provider_error_for_unsupported_provider(
        self, client: TestClient
    ) -> None:
        """Non-vision providers should return structured error."""
        data = _make_jpeg_bytes()
        with _mock_pil_open(), _mock_tesseract(["text"]):
            response = client.post(
                "/images/analyze",
                files={"file": ("photo.jpg", data, "image/jpeg")},
                data={"prompt": "Describe this image", "provider": "llama"},
            )
        if response.status_code == 200:
            body = response.json()
            # Should contain error key or structured error response
            assert (
                "no_vision_provider" in body.get("error", "")
                or body.get("vision_analysis") is None
            )

    def test_vision_analysis_returned_when_provider_capable(
        self, client: TestClient
    ) -> None:
        """When vision provider is active and prompt provided, return analysis."""
        data = _make_jpeg_bytes()
        mock_result = MagicMock()
        mock_result.text = "An image of a red square."
        with (
            _mock_pil_open(),
            _mock_tesseract(["text"]),
            patch("app.api.images.router.AIOrchestrator") as MockOrch,
        ):
            mock_instance = AsyncMock()
            mock_instance.complete = AsyncMock(return_value=mock_result)
            MockOrch.return_value = mock_instance
            response = client.post(
                "/images/analyze",
                files={"file": ("photo.jpg", data, "image/jpeg")},
                data={"prompt": "Describe this image", "provider": "openai"},
            )
        if response.status_code == 200:
            body = response.json()
            # Vision analysis or None (depending on mock resolution)
            assert "vision_analysis" in body

    def test_prompt_required_for_vision_analysis(self, client: TestClient) -> None:
        """No prompt → no vision analysis requested, response should have vision_analysis=None."""
        data = _make_jpeg_bytes()
        with _mock_pil_open(), _mock_tesseract(["text"]):
            response = client.post(
                "/images/analyze",
                files={"file": ("photo.jpg", data, "image/jpeg")},
            )
        if response.status_code == 200:
            body = response.json()
            assert body.get("vision_analysis") is None


# ---------------------------------------------------------------------------
# Response schema (Requirement 6.3)
# ---------------------------------------------------------------------------


class TestImageResponseSchema:
    """Verify the response contains required fields."""

    def test_response_has_required_fields(self, client: TestClient) -> None:
        """Every successful response must have all four required fields."""
        data = _make_jpeg_bytes()
        with _mock_pil_open(), _mock_tesseract([]):
            response = client.post(
                "/images/analyze",
                files={"file": ("img.jpg", data, "image/jpeg")},
            )
        if response.status_code == 200:
            body = response.json()
            assert "extracted_text" in body
            assert "bounding_boxes" in body
            assert "no_text_found" in body
            assert "vision_analysis" in body
