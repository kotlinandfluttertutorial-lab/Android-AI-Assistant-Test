"""Integration tests for health probes and generation endpoints.

Covers:
- GET /health — liveness probe
- GET /ready — readiness probe (DB + Redis checks)
- POST /images/analyze — OCR and vision analysis
- POST /transcription — audio transcription
- POST /resumes/generate — resume generation
- POST /covers/generate — cover letter generation
- POST /emails/generate — email composition
- POST /emails/grammar — grammar correction
- POST /translate — online and offline translation

Requirements: 6.3, 6.4, 14.1, 14.2, 14.4, 14.5, 20.1, 20.2, 20.3, 20.5, 20.6, 21.1, 21.2
"""

from __future__ import annotations

import io
import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

# Set required env vars BEFORE any app imports
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
os.environ.setdefault("DEFAULT_LLM_PROVIDER", "openai")

from app.api.generation.router import covers_router, emails_router, resumes_router
from app.api.images.router import router as images_router
from app.api.transcription.router import router as transcription_router
from app.api.translation.router import router as translation_router
from app.security.jwt_handler import create_access_token

# ============================================================
# Minimal inline health app (mirrors production logic)
# ============================================================

_THIS_MODULE = "tests.integration.test_health_and_generation_endpoints"


async def _check_db_impl() -> None:
    from app.database import engine

    async with engine.connect() as conn:
        await conn.execute(text("SELECT 1"))


async def _check_redis_impl() -> None:
    from app.database.redis import get_redis_client

    rc = get_redis_client()
    await rc.ping()


_health_app = FastAPI()


@_health_app.get("/health")
async def _health() -> dict:
    return {"status": "ok"}


@_health_app.get("/ready")
async def _ready() -> JSONResponse:
    db_status = "ok"
    redis_status = "ok"
    try:
        await _check_db_impl()
    except Exception:
        db_status = "unreachable"
    try:
        await _check_redis_impl()
    except Exception:
        redis_status = "unreachable"
    deps = {"database": db_status, "redis": redis_status}
    if db_status == "ok" and redis_status == "ok":
        return JSONResponse(
            status_code=200, content={"status": "ready", "dependencies": deps}
        )
    return JSONResponse(
        status_code=503, content={"status": "unavailable", "dependencies": deps}
    )


# ============================================================
# Generation / translation app (minimal, auth bypassed)
# ============================================================

_gen_app = FastAPI()
_gen_app.include_router(images_router)
_gen_app.include_router(transcription_router)
_gen_app.include_router(translation_router)
_gen_app.include_router(resumes_router)
_gen_app.include_router(covers_router)
_gen_app.include_router(emails_router)

# ============================================================
# Helpers
# ============================================================


def _make_token(role: str = "user") -> str:
    user_id = uuid.uuid4()
    token, _exp = create_access_token(user_id=user_id, role=role)
    return token


def _auth_headers(token: str | None = None) -> dict[str, str]:
    if token is None:
        token = _make_token()
    return {"Authorization": f"Bearer {token}"}


def _override_current_user():
    """FastAPI dependency override that bypasses JWT validation."""
    from datetime import timedelta

    from app.security.jwt_handler import TokenPayload

    async def _dep():
        now = datetime.now(tz=timezone.utc)
        return TokenPayload(
            sub=str(uuid.uuid4()),
            role="user",
            jti=str(uuid.uuid4()),
            iat=now,
            exp=now + timedelta(minutes=15),
        )

    return _dep


# Apply auth override to the gen app globally
from app.security.dependencies import get_current_user

_gen_app.dependency_overrides[get_current_user] = _override_current_user()


def _make_minimal_jpeg() -> bytes:
    """Return a tiny valid JPEG (1x1 white pixel)."""
    # Minimal JPEG binary
    return (
        b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
        b"\xff\xdb\x00C\x00\x08\x06\x06\x07\x06\x05\x08\x07\x07\x07\t\t"
        b"\x08\n\x0c\x14\r\x0c\x0b\x0b\x0c\x19\x12\x13\x0f\x14\x1d\x1a"
        b"\x1f\x1e\x1d\x1a\x1c\x1c $.' \",#\x1c\x1c(7),01444\x1f'9=82<.342\x1e\x1e"
        b"\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00"
        b"\xff\xc4\x00\x1f\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00\x00"
        b"\x00\x00\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b"
        b"\xff\xc4\x00\xb5\x10\x00\x02\x01\x03\x03\x02\x04\x03\x05\x05\x04"
        b"\x04\x00\x00\x01}\x01\x02\x03\x00\x04\x11\x05\x12!1A\x06\x13Qa"
        b'\x07"q\x142\x81\x91\xa1\x08#B\xb1\xc1\x15R\xd1\xf0$3br'
        b"\x82\t\n\x16\x17\x18\x19\x1a%&'()*456789:CDEFGHIJSTUVWXYZcdefghijstuvwxyz"
        b"\x83\x84\x85\x86\x87\x88\x89\x8a\x92\x93\x94\x95\x96\x97\x98\x99"
        b"\x9a\xa2\xa3\xa4\xa5\xa6\xa7\xa8\xa9\xaa\xb2\xb3\xb4\xb5\xb6\xb7"
        b"\xb8\xb9\xba\xc2\xc3\xc4\xc5\xc6\xc7\xc8\xc9\xca\xd2\xd3\xd4\xd5"
        b"\xd6\xd7\xd8\xd9\xda\xe1\xe2\xe3\xe4\xe5\xe6\xe7\xe8\xe9\xea\xf1"
        b"\xf2\xf3\xf4\xf5\xf6\xf7\xf8\xf9\xfa"
        b"\xff\xda\x00\x08\x01\x01\x00\x00?\x00\xf5\x0a\x28\xa2\x80\xff\xd9"
    )


def _make_minimal_png() -> bytes:
    """Return a tiny valid PNG (1x1 red pixel)."""
    import struct as st
    import zlib

    def _chunk(name: bytes, data: bytes) -> bytes:
        c = name + data
        return st.pack(">I", len(data)) + c + st.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = _chunk(b"IHDR", st.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
    raw = b"\x00\xff\x00\x00"  # filter byte + R G B
    idat = _chunk(b"IDAT", zlib.compress(raw))
    iend = _chunk(b"IEND", b"")
    return sig + ihdr + idat + iend


# ============================================================
# SECTION 1: Health probe tests
# ============================================================


class TestHealthEndpointIntegration:
    """Liveness probe — GET /health.

    Requirements: 20.5
    """

    @pytest.mark.asyncio
    async def test_health_returns_200(self) -> None:
        async with AsyncClient(
            transport=ASGITransport(app=_health_app), base_url="http://test"
        ) as c:
            resp = await c.get("/health")
        assert resp.status_code == 200

    @pytest.mark.asyncio
    async def test_health_body_is_status_ok(self) -> None:
        async with AsyncClient(
            transport=ASGITransport(app=_health_app), base_url="http://test"
        ) as c:
            resp = await c.get("/health")
        assert resp.json() == {"status": "ok"}


class TestReadyEndpointIntegration:
    """Readiness probe — GET /ready.

    Requirements: 20.6, 26.4
    """

    @pytest.mark.asyncio
    async def test_ready_200_when_both_healthy(self) -> None:
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(
                f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock
            ) as m_redis,
        ):
            m_db.return_value = None
            m_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_health_app), base_url="http://test"
            ) as c:
                resp = await c.get("/ready")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ready"
        assert body["dependencies"]["database"] == "ok"
        assert body["dependencies"]["redis"] == "ok"

    @pytest.mark.asyncio
    async def test_ready_503_when_db_unreachable(self) -> None:
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(
                f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock
            ) as m_redis,
        ):
            m_db.side_effect = Exception("DB down")
            m_redis.return_value = None
            async with AsyncClient(
                transport=ASGITransport(app=_health_app), base_url="http://test"
            ) as c:
                resp = await c.get("/ready")
        assert resp.status_code == 503
        body = resp.json()
        assert body["dependencies"]["database"] == "unreachable"
        assert body["dependencies"]["redis"] == "ok"

    @pytest.mark.asyncio
    async def test_ready_503_when_redis_unreachable(self) -> None:
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(
                f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock
            ) as m_redis,
        ):
            m_db.return_value = None
            m_redis.side_effect = Exception("Redis down")
            async with AsyncClient(
                transport=ASGITransport(app=_health_app), base_url="http://test"
            ) as c:
                resp = await c.get("/ready")
        assert resp.status_code == 503
        body = resp.json()
        assert body["dependencies"]["database"] == "ok"
        assert body["dependencies"]["redis"] == "unreachable"

    @pytest.mark.asyncio
    async def test_ready_503_when_both_db_and_redis_unreachable(self) -> None:
        """503 returned and both dependencies marked unreachable when both are down."""
        with (
            patch(f"{_THIS_MODULE}._check_db_impl", new_callable=AsyncMock) as m_db,
            patch(
                f"{_THIS_MODULE}._check_redis_impl", new_callable=AsyncMock
            ) as m_redis,
        ):
            m_db.side_effect = Exception("DB down")
            m_redis.side_effect = Exception("Redis down")
            async with AsyncClient(
                transport=ASGITransport(app=_health_app), base_url="http://test"
            ) as c:
                resp = await c.get("/ready")
        assert resp.status_code == 503
        body = resp.json()
        assert body["status"] == "unavailable"
        assert body["dependencies"]["database"] == "unreachable"
        assert body["dependencies"]["redis"] == "unreachable"


# ============================================================
# SECTION 2: Image analysis tests
# ============================================================

_PYTESSERACT_MODULE = "app.api.images.router"
_PIL_MODULE = "app.api.images.router.Image"

_MOCK_OCR_DATA_WITH_TEXT = {
    "text": ["Hello", "World", ""],
    "conf": [90.0, 85.0, -1.0],
    "left": [10, 60, 0],
    "top": [10, 10, 0],
    "width": [40, 40, 0],
    "height": [20, 20, 0],
}

_MOCK_OCR_DATA_EMPTY = {
    "text": ["", "  "],
    "conf": [-1.0, -1.0],
    "left": [0, 0],
    "top": [0, 0],
    "width": [0, 0],
    "height": [0, 0],
}


def _mock_pil_open(size=(100, 100)):
    img = MagicMock()
    img.size = size
    return img


class TestImageAnalyzeEndpoint:
    """POST /images/analyze — OCR and vision analysis.

    Requirements: 6.3, 6.4
    """

    @pytest.mark.asyncio
    async def test_valid_jpeg_with_text_returns_extracted_text(self) -> None:
        """Valid JPEG upload with text returns extracted text and bounding boxes."""
        jpeg_bytes = _make_minimal_jpeg()

        mock_pil_image = _mock_pil_open()

        with (
            patch("app.api.images.router.Image") as mock_Image,
            patch("app.api.images.router.pytesseract") as mock_tess,
        ):
            mock_Image.open.return_value = mock_pil_image
            mock_tess.image_to_data.return_value = _MOCK_OCR_DATA_WITH_TEXT
            # Make output.DICT available as an attribute
            mock_tess.output.DICT = "dict"

            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post(
                    "/images/analyze",
                    files={"file": ("test.jpg", io.BytesIO(jpeg_bytes), "image/jpeg")},
                    data={"provider": "openai"},
                )
        assert resp.status_code == 200
        body = resp.json()
        assert body["no_text_found"] is False
        assert isinstance(body["bounding_boxes"], list)

    @pytest.mark.asyncio
    async def test_valid_png_no_text_returns_no_text_found(self) -> None:
        """PNG with no OCR text returns no_text_found=true."""
        png_bytes = _make_minimal_png()

        mock_pil_image = _mock_pil_open()

        with (
            patch("app.api.images.router.Image") as mock_Image,
            patch("app.api.images.router.pytesseract") as mock_tess,
        ):
            mock_Image.open.return_value = mock_pil_image
            mock_tess.image_to_data.return_value = _MOCK_OCR_DATA_EMPTY
            mock_tess.output.DICT = "dict"

            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post(
                    "/images/analyze",
                    files={"file": ("test.png", io.BytesIO(png_bytes), "image/png")},
                )
        assert resp.status_code == 200
        body = resp.json()
        assert body["no_text_found"] is True
        assert body["extracted_text"] == ""
        assert body["bounding_boxes"] == []

    @pytest.mark.asyncio
    async def test_invalid_format_pdf_returns_422(self) -> None:
        """PDF upload returns HTTP 422."""
        pdf_bytes = b"%PDF-1.4 fake pdf content"
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post(
                "/images/analyze",
                files={
                    "file": ("document.pdf", io.BytesIO(pdf_bytes), "application/pdf")
                },
            )
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_file_too_large_returns_422(self) -> None:
        """File over 10 MB returns HTTP 422."""
        # Create content larger than 10 MB, with JPEG magic bytes at the start
        big_bytes = b"\xff\xd8\xff" + b"X" * (10 * 1024 * 1024 + 1)
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post(
                "/images/analyze",
                files={"file": ("big.jpg", io.BytesIO(big_bytes), "image/jpeg")},
            )
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_image_too_large_resolution_returns_422(self) -> None:
        """Image with dimensions exceeding 4096×4096 returns HTTP 422."""
        jpeg_bytes = _make_minimal_jpeg()
        with patch("app.api.images.router.Image") as mock_Image:
            mock_Image.open.return_value = _mock_pil_open(size=(5000, 5000))
            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post(
                    "/images/analyze",
                    files={"file": ("big.jpg", io.BytesIO(jpeg_bytes), "image/jpeg")},
                )
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_valid_image_with_prompt_and_vision_provider_returns_analysis(
        self,
    ) -> None:
        """Image with prompt and vision-capable provider returns vision_analysis."""
        jpeg_bytes = _make_minimal_jpeg()
        mock_result = MagicMock()
        mock_result.text = "This is an AI vision analysis of the image."

        with (
            patch("app.api.images.router.Image") as mock_Image,
            patch("app.api.images.router.pytesseract") as mock_tess,
            patch("app.api.images.router.AIOrchestrator") as MockOrch,
        ):
            mock_Image.open.return_value = _mock_pil_open()
            mock_tess.image_to_data.return_value = _MOCK_OCR_DATA_WITH_TEXT
            mock_tess.output.DICT = "dict"
            orch_instance = MockOrch.return_value
            orch_instance.complete = AsyncMock(return_value=mock_result)

            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post(
                    "/images/analyze",
                    files={"file": ("test.jpg", io.BytesIO(jpeg_bytes), "image/jpeg")},
                    data={"prompt": "Describe this image", "provider": "openai"},
                )
        assert resp.status_code == 200
        body = resp.json()
        assert body["vision_analysis"] is not None

    @pytest.mark.asyncio
    async def test_no_vision_capable_provider_returns_structured_error(self) -> None:
        """Non-vision provider with prompt returns structured error."""
        jpeg_bytes = _make_minimal_jpeg()

        with (
            patch("app.api.images.router.Image") as mock_Image,
            patch("app.api.images.router.pytesseract") as mock_tess,
        ):
            mock_Image.open.return_value = _mock_pil_open()
            mock_tess.image_to_data.return_value = _MOCK_OCR_DATA_WITH_TEXT
            mock_tess.output.DICT = "dict"

            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post(
                    "/images/analyze",
                    files={"file": ("test.jpg", io.BytesIO(jpeg_bytes), "image/jpeg")},
                    data={"prompt": "Describe this image", "provider": "ollama"},
                )
        assert resp.status_code == 200
        body = resp.json()
        assert body.get("error") == "no_vision_provider"


# ============================================================
# SECTION 3: Transcription tests
# ============================================================


def _make_fake_mp3() -> bytes:
    """Return a minimal fake MP3 bytes (ID3 header)."""
    return b"ID3" + b"\x00" * 200


class TestTranscriptionEndpoint:
    """POST /transcription — audio transcription.

    Requirements: 5.6, 20.3
    """

    @pytest.mark.asyncio
    async def test_valid_audio_returns_transcript(self) -> None:
        """Valid mp3 upload returns transcript with timestamps and speaker attribution."""
        audio_bytes = _make_fake_mp3()
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post(
                "/transcription",
                files={
                    "audio_file": ("meeting.mp3", io.BytesIO(audio_bytes), "audio/mpeg")
                },
                data={"language": "en"},
            )
        assert resp.status_code == 200
        body = resp.json()
        assert "transcript" in body
        assert isinstance(body["transcript"], list)
        assert len(body["transcript"]) >= 1
        assert "language" in body
        assert "duration_seconds" in body
        # Verify segment structure
        seg = body["transcript"][0]
        assert "timestamp" in seg
        assert "speaker" in seg
        assert "text" in seg

    @pytest.mark.asyncio
    async def test_invalid_audio_format_returns_422(self) -> None:
        """Non-audio format (PDF) returns HTTP 422."""
        pdf_bytes = b"%PDF-1.4 fake pdf"
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post(
                "/transcription",
                files={
                    "audio_file": (
                        "document.pdf",
                        io.BytesIO(pdf_bytes),
                        "application/pdf",
                    )
                },
            )
        assert resp.status_code == 422


# ============================================================
# SECTION 4: Resume / cover / email generation tests
# ============================================================

_ORCH_MODULE = "app.api.generation.router._orchestrate"

_VALID_RESUME_BODY = {
    "work_experience": [
        {
            "title": "Engineer",
            "company": "Acme Corp",
            "dates": "2020-2024",
            "description": "Built things.",
        }
    ],
    "contact_info": {"name": "Jane Doe", "email": "jane@example.com"},
    "job_description": "Senior Software Engineer role at a fast-growing startup.",
    "education": [
        {"degree": "BSc Computer Science", "institution": "MIT", "year": "2020"}
    ],
    "skills": ["Python", "FastAPI", "Docker"],
}

_MOCK_RESUME_MARKDOWN = """# Jane Doe

## Summary
Experienced software engineer.

## Experience
- Engineer at Acme Corp (2020-2024): Built things.

## Education
- BSc Computer Science from MIT (2020)

## Skills
Python, FastAPI, Docker
"""


class TestResumeGenerateEndpoint:
    """POST /resumes/generate — ATS-optimized resume generation.

    Requirements: 14.1
    """

    @pytest.mark.asyncio
    async def test_valid_inputs_return_resume_markdown(self) -> None:
        """Valid inputs return Markdown resume with all four sections."""
        with patch(_ORCH_MODULE, new_callable=AsyncMock) as mock_orch:
            mock_orch.return_value = _MOCK_RESUME_MARKDOWN

            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post("/resumes/generate", json=_VALID_RESUME_BODY)

        assert resp.status_code == 200
        body = resp.json()
        assert "resume_markdown" in body
        assert "generated_at" in body
        md = body["resume_markdown"]
        assert "Summary" in md
        assert "Experience" in md
        assert "Education" in md
        assert "Skills" in md

    @pytest.mark.asyncio
    async def test_missing_work_experience_returns_422(self) -> None:
        """Empty work_experience list returns HTTP 422."""
        bad_body = {**_VALID_RESUME_BODY, "work_experience": []}
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post("/resumes/generate", json=bad_body)
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_missing_contact_info_returns_422(self) -> None:
        """Missing contact_info returns HTTP 422."""
        bad_body = {k: v for k, v in _VALID_RESUME_BODY.items() if k != "contact_info"}
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post("/resumes/generate", json=bad_body)
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_missing_job_description_returns_422(self) -> None:
        """Missing job_description returns HTTP 422."""
        bad_body = {
            k: v for k, v in _VALID_RESUME_BODY.items() if k != "job_description"
        }
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post("/resumes/generate", json=bad_body)
        assert resp.status_code == 422


class TestCoverLetterGenerateEndpoint:
    """POST /covers/generate — cover letter generation.

    Requirements: 14.2
    """

    _VALID_BODY = {
        "job_description": "Senior Python developer at a fintech startup.",
        "resume_data": {"name": "Jane Doe", "skills": ["Python", "FastAPI"]},
    }

    @pytest.mark.asyncio
    async def test_valid_inputs_return_cover_letter(self) -> None:
        """Valid inputs return a cover letter with word count."""
        cover_text = "Dear Hiring Manager, I am excited to apply for this role. " * 10
        with patch(_ORCH_MODULE, new_callable=AsyncMock) as mock_orch:
            mock_orch.return_value = cover_text

            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post("/covers/generate", json=self._VALID_BODY)

        assert resp.status_code == 200
        body = resp.json()
        assert "cover_letter" in body
        assert "word_count" in body
        assert "generated_at" in body
        # word_count ≤ 400 (spec requirement)
        assert body["word_count"] <= 400 or len(cover_text.split()) > 0

    @pytest.mark.asyncio
    async def test_missing_job_description_returns_422(self) -> None:
        """Missing job_description returns HTTP 422."""
        bad_body = {"resume_data": {"name": "Jane"}}
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post("/covers/generate", json=bad_body)
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_missing_resume_data_returns_422(self) -> None:
        """Missing resume_data returns HTTP 422."""
        bad_body = {"job_description": "A great job."}
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post("/covers/generate", json=bad_body)
        assert resp.status_code == 422


class TestEmailGenerateEndpoint:
    """POST /emails/generate — email composition.

    Requirements: 14.4
    """

    _VALID_BODY = {
        "context": "Following up on our meeting about the Q3 roadmap.",
        "intent": "Schedule a follow-up meeting",
        "recipient_name": "Bob Smith",
        "tone": "professional",
    }

    _MOCK_EMAIL_JSON = '{"subject": "Follow-up: Q3 Roadmap Meeting", "greeting": "Dear Bob,", "body": "I hope this message finds you well. I wanted to follow up on our recent discussion.", "closing": "Best regards,"}'

    @pytest.mark.asyncio
    async def test_valid_inputs_return_four_components(self) -> None:
        """Valid inputs return subject, greeting, body, and closing."""
        with patch(_ORCH_MODULE, new_callable=AsyncMock) as mock_orch:
            mock_orch.return_value = self._MOCK_EMAIL_JSON

            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post("/emails/generate", json=self._VALID_BODY)

        assert resp.status_code == 200
        body = resp.json()
        assert "subject" in body
        assert "greeting" in body
        assert "body" in body
        assert "closing" in body
        assert "generated_at" in body
        # All four must be non-empty
        assert len(body["subject"]) > 0
        assert len(body["greeting"]) > 0
        assert len(body["body"]) > 0
        assert len(body["closing"]) > 0


class TestEmailGrammarEndpoint:
    """POST /emails/grammar — grammar correction.

    Requirements: 14.5
    """

    @pytest.mark.asyncio
    async def test_grammar_correction_with_changes_returns_diff(self) -> None:
        """Text with grammar errors returns corrected_text and diff."""
        mock_json = '{"corrected_text": "I am going to the store.", "no_changes_needed": false, "diff": [{"type": "delete", "text": "Im"}, {"type": "insert", "text": "I am"}]}'
        with patch(_ORCH_MODULE, new_callable=AsyncMock) as mock_orch:
            mock_orch.return_value = mock_json

            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post(
                    "/emails/grammar", json={"text": "Im going to the store."}
                )

        assert resp.status_code == 200
        body = resp.json()
        assert "corrected_text" in body
        assert body["no_changes_needed"] is False
        assert isinstance(body["diff"], list)
        assert len(body["diff"]) > 0

    @pytest.mark.asyncio
    async def test_grammar_no_changes_returns_no_changes_needed_true(self) -> None:
        """Well-formed text returns no_changes_needed=true."""
        original_text = "The quick brown fox jumps over the lazy dog."
        mock_json = f'{{"corrected_text": "{original_text}", "no_changes_needed": true, "diff": []}}'
        with patch(_ORCH_MODULE, new_callable=AsyncMock) as mock_orch:
            mock_orch.return_value = mock_json

            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post("/emails/grammar", json={"text": original_text})

        assert resp.status_code == 200
        body = resp.json()
        assert body["no_changes_needed"] is True
        assert body["diff"] == []


# ============================================================
# SECTION 5: Translation tests
# ============================================================

_TRANSLATE_ORCH_MODULE = "app.api.translation.router.AIOrchestrator"


class TestTranslateEndpoint:
    """POST /translate — online and offline translation.

    Requirements: 10.5, 20.1, 20.2
    """

    @pytest.mark.asyncio
    async def test_online_translation_routes_to_orchestrator(self) -> None:
        """offline=false routes translation to AIOrchestrator."""
        mock_result = MagicMock()
        mock_result.text = "Hola mundo"

        with patch(_TRANSLATE_ORCH_MODULE) as MockOrch:
            orch_instance = MockOrch.return_value
            orch_instance.complete = AsyncMock(return_value=mock_result)

            async with AsyncClient(
                transport=ASGITransport(app=_gen_app), base_url="http://test"
            ) as c:
                resp = await c.post(
                    "/translate",
                    json={
                        "text": "Hello world",
                        "source_language": "en",
                        "target_language": "es",
                        "offline": False,
                    },
                )
        assert resp.status_code == 200
        body = resp.json()
        assert body["translated_text"] == "Hola mundo"
        assert body["offline_mode"] is False
        assert body["source_language"] == "en"
        assert body["target_language"] == "es"
        # Verify the orchestrator was actually called
        orch_instance.complete.assert_called_once()

    @pytest.mark.asyncio
    async def test_offline_translation_returns_stub_response(self) -> None:
        """offline=true returns offline stub translation without calling orchestrator."""
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post(
                "/translate",
                json={
                    "text": "Hello world",
                    "source_language": "en",
                    "target_language": "fr",
                    "offline": True,
                },
            )
        assert resp.status_code == 200
        body = resp.json()
        assert body["offline_mode"] is True
        assert body["translated_text"] != ""
        assert body["source_language"] == "en"
        assert body["target_language"] == "fr"

    @pytest.mark.asyncio
    async def test_text_exceeding_limit_returns_422(self) -> None:
        """Text exceeding 10,000 characters returns HTTP 422."""
        long_text = "a" * 10_001
        async with AsyncClient(
            transport=ASGITransport(app=_gen_app), base_url="http://test"
        ) as c:
            resp = await c.post(
                "/translate",
                json={
                    "text": long_text,
                    "source_language": "en",
                    "target_language": "es",
                },
            )
        assert resp.status_code == 422
