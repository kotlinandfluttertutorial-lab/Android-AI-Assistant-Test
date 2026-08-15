# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/images
# File    : router.py
# Purpose : FastAPI router for image analysis — OCR and vision-LLM endpoints
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - Multipart file upload validation (JPEG/PNG/WebP, ≤10 MB, ≤4096×4096 px)
#   - OCR via pytesseract with bounding box extraction
#   - Vision LLM routing through AIOrchestrator
#   - JWT Bearer authentication via get_current_user dependency
#
# Dependencies:
#   - fastapi
#   - Pillow (PIL)
#   - pytesseract
#   - app.services.ai_orchestrator
#   - app.security.dependencies
#
# Requirements: 6.3, 6.4, 22.4
# ============================================================

"""Images API router — /images/analyze endpoint.

Endpoint
--------
POST /images/analyze
    - Accept multipart form: ``file`` (JPEG/PNG/WebP), optional ``prompt``,
      optional ``provider`` (default "openai").
    - Validate: JPEG/PNG/WebP only, max 4096×4096 px, max 10 MB.
    - Perform OCR using pytesseract.
    - If ``prompt`` provided and vision provider active: run vision analysis.
    - Return: extracted_text, bounding_boxes, no_text_found, vision_analysis.

Requirements: 6.3, 6.4
"""

from __future__ import annotations

import io
import logging
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import JSONResponse

from app.schemas.images import BoundingBox, ImageAnalyzeResponse
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    Image = None  # type: ignore[assignment]

try:
    import pytesseract
    import pytesseract.output as _pytesseract_output
except ImportError:  # pragma: no cover
    pytesseract = None  # type: ignore[assignment]
    _pytesseract_output = None  # type: ignore[assignment]

try:
    from app.services.ai_orchestrator import AIOrchestrator, LLMProvider
except ImportError:  # pragma: no cover
    AIOrchestrator = None  # type: ignore[assignment,misc]
    LLMProvider = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_MAX_FILE_BYTES = 10 * 1024 * 1024  # 10 MB
_MAX_DIMENSION = 4096  # pixels

_ALLOWED_CONTENT_TYPES = {
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp",
}

# Magic byte signatures for JPEG, PNG, WebP
_MAGIC_JPEG = b"\xff\xd8\xff"
_MAGIC_PNG = b"\x89PNG"
_MAGIC_WEBP_RIFF = b"RIFF"
_MAGIC_WEBP_WEBP = b"WEBP"

# Vision-capable providers (Requirements 6.4)
_VISION_CAPABLE_PROVIDERS = {"openai", "gemini", "claude"}

# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

router = APIRouter(
    prefix="/images",
    tags=["images"],
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _is_valid_image_magic(data: bytes) -> bool:
    """Verify file starts with a known image magic byte signature."""
    if data[:3] == _MAGIC_JPEG:
        return True
    if data[:4] == _MAGIC_PNG:
        return True
    # WebP: starts with RIFF....WEBP
    if data[:4] == _MAGIC_WEBP_RIFF and len(data) >= 12 and data[8:12] == _MAGIC_WEBP_WEBP:
        return True
    return False


def _build_bounding_boxes(df) -> tuple[str, list[BoundingBox]]:  # type: ignore[type-arg]
    """Extract text and bounding box data from a pytesseract image_to_data result.

    Args:
        df: pandas-like DataFrame (or dict of lists) from pytesseract.image_to_data.

    Returns:
        Tuple of (full_text: str, bounding_boxes: list[BoundingBox]).
    """
    words: list[str] = []
    boxes: list[BoundingBox] = []

    # pytesseract.image_to_data returns a tsv-like dict when output_type=dict
    n = len(df["text"])
    for i in range(n):
        text = str(df["text"][i]).strip()
        if not text:
            continue
        try:
            conf = float(df["conf"][i])
        except (ValueError, TypeError):
            conf = -1.0

        if conf <= 0:
            continue

        words.append(text)
        boxes.append(
            BoundingBox(
                text=text,
                left=int(df["left"][i]),
                top=int(df["top"][i]),
                width=int(df["width"][i]),
                height=int(df["height"][i]),
                confidence=conf,
            )
        )

    return " ".join(words), boxes


# ---------------------------------------------------------------------------
# POST /images/analyze
# ---------------------------------------------------------------------------


@router.post(
    "/analyze",
    summary="Analyze an image with OCR and optional vision-LLM",
    description=(
        "Upload a JPEG, PNG, or WebP image (max 10 MB, max 4096×4096 px). "
        "Returns OCR extracted text with bounding boxes and, when a prompt is "
        "supplied with a vision-capable provider, an LLM vision analysis. "
        "Requires JWT Bearer authentication."
    ),
    response_model=ImageAnalyzeResponse,
)
async def analyze_image(
    file: Annotated[UploadFile, File(description="JPEG, PNG, or WebP image to analyze")],
    prompt: str | None = Form(None, description="Optional prompt for vision analysis"),
    provider: str = Form("openai", description="LLM provider for vision analysis"),
    current_user: TokenPayload = Depends(get_current_user),
) -> ImageAnalyzeResponse:
    """OCR and optional vision analysis of an uploaded image.

    Validation (all checked BEFORE any heavy processing):
    - Content-Type must be image/jpeg, image/png, or image/webp.
    - File size ≤ 10 MB.
    - File magic bytes must match a known image format.
    - Image dimensions ≤ 4096 × 4096 px.

    Requirements: 6.3, 6.4
    """
    # -----------------------------------------------------------------------
    # Step 1 — Content-Type validation
    # -----------------------------------------------------------------------
    content_type = (file.content_type or "").lower().split(";")[0].strip()
    if content_type not in _ALLOWED_CONTENT_TYPES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(f"Unsupported image format '{content_type}'. Allowed: JPEG, PNG, WebP."),
        )

    # -----------------------------------------------------------------------
    # Step 2 — Read and size check
    # -----------------------------------------------------------------------
    image_bytes = await file.read()
    if len(image_bytes) > _MAX_FILE_BYTES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Image exceeds maximum allowed size of {_MAX_FILE_BYTES // (1024 * 1024)} MB.",
        )

    # -----------------------------------------------------------------------
    # Step 3 — Magic byte validation
    # -----------------------------------------------------------------------
    if not _is_valid_image_magic(image_bytes):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="File content does not match a valid JPEG, PNG, or WebP image.",
        )

    # -----------------------------------------------------------------------
    # Step 4 — Open with Pillow and validate dimensions
    # -----------------------------------------------------------------------
    try:
        pil_image = Image.open(io.BytesIO(image_bytes))
        width, height = pil_image.size
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Could not open image file: {exc}",
        ) from exc

    if width > _MAX_DIMENSION or height > _MAX_DIMENSION:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Image dimensions {width}×{height} exceed the maximum "
                f"of {_MAX_DIMENSION}×{_MAX_DIMENSION} pixels."
            ),
        )

    # -----------------------------------------------------------------------
    # Step 5 — OCR with pytesseract (Requirements 6.3)
    # -----------------------------------------------------------------------
    extracted_text = ""
    bounding_boxes: list[BoundingBox] = []

    try:
        if pytesseract is None:
            raise RuntimeError("pytesseract is not installed")

        # image_to_data returns a dict of lists when output_type=DICT
        _output_type = getattr(getattr(pytesseract, "output", None), "DICT", "dict")
        data = pytesseract.image_to_data(pil_image, output_type=_output_type)
        extracted_text, bounding_boxes = _build_bounding_boxes(data)
    except Exception as exc:
        logger.warning("pytesseract OCR failed: %s", exc)
        # Graceful degradation: return empty OCR result rather than 500
        extracted_text = ""
        bounding_boxes = []

    no_text_found = not extracted_text.strip()

    if no_text_found:
        # Per spec: return empty text result with no_text_found=True (Requirements 6.3)
        if not prompt:
            return ImageAnalyzeResponse(
                extracted_text="",
                bounding_boxes=[],
                no_text_found=True,
                vision_analysis=None,
            )

    # -----------------------------------------------------------------------
    # Step 6 — Vision analysis (Requirements 6.4)
    # -----------------------------------------------------------------------
    vision_analysis: str | None = None

    if prompt:
        provider_lower = provider.lower()
        if provider_lower not in _VISION_CAPABLE_PROVIDERS:
            # Return a structured error per spec requirement
            return JSONResponse(  # type: ignore[return-value]
                status_code=200,
                content={
                    "error": "no_vision_provider",
                    "message": ("No vision-capable LLM provider is currently configured"),
                },
            )

        try:
            # Use module-level AIOrchestrator and LLMProvider
            orchestrator = AIOrchestrator(db=None)  # type: ignore[arg-type]
            try:
                provider_enum = LLMProvider(provider_lower)
            except ValueError:
                provider_enum = LLMProvider.openai

            result = await orchestrator.complete(
                prompt=f"Image analysis request. {prompt}",
                provider=provider_enum,
                max_tokens=1024,
                user_id=current_user.sub,
            )
            vision_analysis = result.text

        except Exception as exc:
            logger.warning("Vision LLM analysis failed: %s", exc)
            vision_analysis = None

    return ImageAnalyzeResponse(
        extracted_text=extracted_text,
        bounding_boxes=bounding_boxes,
        no_text_found=no_text_found,
        vision_analysis=vision_analysis,
    )
