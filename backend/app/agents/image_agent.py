# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : image_agent.py
# Purpose : ImageAgent — wraps the existing /images/analyze endpoint
#           (ImageAnalysisService) as an Agent without creating a
#           second implementation.
#
# Architecture Layer : Agent Core (Phase 6)
# Pattern Used       : Adapter (implements Agent; delegates to service)
#
# Key Concepts:
#   - Does NOT create a new image analysis path; calls the same
#     ImageAnalysisService that the /images/analyze router uses
#   - Supports actions: "ocr", "vision" (default "ocr")
#   - Image data supplied as base64-encoded bytes in metadata
#   - No raw file bytes returned — only extracted text / vision answer
#
# Request metadata keys:
#   "image_action"  — "ocr" | "vision" (default "ocr")
#   "image_base64"  — Base64-encoded JPEG/PNG bytes (required)
#   "prompt"        — Vision Q&A question (for "vision" action)
#   "provider"      — LLM provider for vision analysis (default "gemini")
#
# Streaming protocol:
#   Started → StatusChanged(RUNNING) → Thinking → Token → Completed
# ============================================================

"""ImageAgent — wraps ImageAnalysisService as an Agent."""

from __future__ import annotations

import base64
import io
import logging
from collections.abc import AsyncIterator

from app.agents.base import Agent
from app.agents.models import (
    AgentCapability,
    AgentCompletedEvent,
    AgentError,
    AgentEvent,
    AgentExecution,
    AgentFailedEvent,
    AgentRequest,
    AgentResult,
    AgentStartedEvent,
    AgentStatus,
    AgentStatusChangedEvent,
    AgentThinkingEvent,
    AgentTokenEvent,
)

logger = logging.getLogger(__name__)

IMAGE_AGENT_NAME = "image-analysis"

# Lazy imports — preserve existing service exactly as-is
try:
    from app.services.image_analysis import (  # type: ignore
        ImageAnalysisService as _ImageAnalysisService,
    )
except Exception:  # pragma: no cover
    _ImageAnalysisService = None  # type: ignore

ImageAnalysisService = _ImageAnalysisService


class ImageAgent(Agent):
    """Agent that analyses images via the existing ImageAnalysisService.

    Does NOT create a second image analysis path — delegates to the
    same service used by the /images/analyze HTTP endpoint.
    """

    @property
    def name(self) -> str:
        return IMAGE_AGENT_NAME

    @property
    def description(self) -> str:
        return (
            "Image analysis agent: OCR text extraction and vision LLM "
            "question answering via the existing ImageAnalysisService."
        )

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset(
            {
                AgentCapability.IMAGE_UNDERSTANDING,
                AgentCapability.TEXT_GENERATION,
                AgentCapability.STREAMING,
            }
        )

    async def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]:
        yield AgentStartedEvent(execution_id=execution.execution_id, agent_name=self.name)
        yield AgentStatusChangedEvent(
            execution_id=execution.execution_id, status=AgentStatus.RUNNING
        )

        if ImageAnalysisService is None:
            yield self._failed(
                execution, request, "SERVICE_UNAVAILABLE", "Image analysis service not available."
            )
            return

        metadata = request.metadata or {}
        action = (metadata.get("image_action") or "ocr").strip().lower()
        image_b64 = (metadata.get("image_base64") or "").strip()
        prompt = (metadata.get("prompt") or request.input or "").strip() or None
        provider = (metadata.get("provider") or "gemini").strip()

        if not image_b64:
            yield self._failed(
                execution,
                request,
                "MISSING_IMAGE",
                "metadata['image_base64'] (base64 JPEG/PNG) is required.",
            )
            return

        # Decode base64 → bytes
        try:
            image_bytes = base64.b64decode(image_b64)
        except Exception as exc:
            yield self._failed(
                execution, request, "INVALID_BASE64", f"Could not decode image_base64: {exc}"
            )
            return

        yield AgentThinkingEvent(step_index=0, thought=f"Processing image ({action})…")

        service = ImageAnalysisService()
        try:
            image_file = io.BytesIO(image_bytes)
            image_file.name = "image.jpg"

            if action == "vision":
                result = await service.analyze_with_vision(
                    image_file=image_file,
                    prompt=prompt or "Describe this image.",
                    provider=provider,
                )
            else:
                # Default: OCR
                result = await service.extract_text(image_file=image_file)

        except Exception as exc:
            logger.warning("ImageAgent: analysis error: %s", exc)
            yield self._failed(
                execution, request, "ANALYSIS_ERROR", f"Image analysis failed: {exc}"
            )
            return

        # Build response text
        content = self._format_result(action, result)

        yield AgentTokenEvent(token=content)
        yield AgentCompletedEvent(
            result=AgentResult(
                execution_id=execution.execution_id,
                request_id=request.request_id,
                agent_name=self.name,
                status=AgentStatus.COMPLETED,
                content=content,
                metadata={"action": action},
            )
        )

    # ── Helpers ───────────────────────────────────────────────────────────────

    @staticmethod
    def _format_result(action: str, result: object) -> str:
        extracted = getattr(result, "extracted_text", "") or ""
        vision = getattr(result, "vision_analysis", "") or ""
        no_text = getattr(result, "no_text_found", False)

        if action == "vision":
            if vision:
                return vision
            if extracted:
                return f"**Extracted text:**\n{extracted}"
            return "Image analysis complete. No content could be extracted."

        # OCR
        if no_text or not extracted:
            return "No text was detected in the image."
        boxes = getattr(result, "bounding_boxes", []) or []
        suffix = f"\n\n_({len(boxes)} text region(s) detected)_" if boxes else ""
        return f"**Extracted text:**\n{extracted}{suffix}"

    @staticmethod
    def _failed(
        execution: AgentExecution,
        request: AgentRequest,
        code: str,
        msg: str,
    ) -> AgentFailedEvent:
        return AgentFailedEvent(
            result=AgentResult(
                execution_id=execution.execution_id,
                request_id=request.request_id,
                agent_name=IMAGE_AGENT_NAME,
                status=AgentStatus.FAILED,
                error=AgentError(code=code, message=msg),
            )
        )
