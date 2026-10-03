# ============================================================
# Android AI Assistant — Backend
# Module  : embedding
# File    : provider.py
# Purpose : SentenceTransformerEmbeddingProvider — concrete
#           IEmbeddingProvider backed by sentence-transformers.
#
# Implements app.interfaces.core.IEmbeddingProvider.
#
# Design rules:
#   - Wraps the same lazy-init + thread-offload pattern used in
#     RAGService._get_embedding_model() — no duplication.
#   - Model name is configurable via EmbeddingConfig (was hardcoded).
#   - Thread-safe: a threading.Lock protects the lazy-load critical
#     section, so multiple coroutines may call embed_batch() in parallel
#     without double-initialising the model.
#   - All heavy CPU work runs in asyncio.to_thread so the event loop
#     is never blocked.
#   - Zero infrastructure dependencies: sentence-transformers is
#     lazy-imported so the module is importable in test environments.
# ============================================================
"""SentenceTransformerEmbeddingProvider — configurable sentence-embeddings."""

from __future__ import annotations

import asyncio
import logging
import threading
from dataclasses import dataclass

from app.interfaces.core import EmbeddingError, EmbeddingVector, IEmbeddingProvider

logger = logging.getLogger(__name__)

# Default model — matches the hardcoded value in RAGService._get_embedding_model()
_DEFAULT_MODEL = "all-MiniLM-L6-v2"
# Dimension emitted by all-MiniLM-L6-v2
_DEFAULT_DIMENSION = 384


@dataclass
class EmbeddingConfig:
    """Configuration for :class:`SentenceTransformerEmbeddingProvider`.

    Attributes:
        model_name:          HuggingFace model identifier.  Defaults to
                             ``"all-MiniLM-L6-v2"`` — identical to the
                             model used by the existing ``RAGService``.
        device:              Compute device passed to SentenceTransformer
                             (``"cpu"``, ``"cuda"``, ``"mps"``).
                             ``"cpu"`` is always safe; the production
                             requirements pin ``sentence-transformers[cpu]``.
        show_progress_bar:   Whether to show a tqdm progress bar during
                             ``model.encode()``.  Always ``False`` in
                             production to avoid polluting structured logs.
        normalize_embeddings: When ``True``, calls SentenceTransformer's
                             built-in L2 normalisation during encode.
                             Cosine similarity then equals dot-product.
    """

    model_name: str = _DEFAULT_MODEL
    device: str = "cpu"
    show_progress_bar: bool = False
    normalize_embeddings: bool = True


class SentenceTransformerEmbeddingProvider(IEmbeddingProvider):
    """Concrete :class:`~app.interfaces.core.IEmbeddingProvider` using
    ``sentence-transformers``.

    Thread-safe lazy initialisation: the underlying
    ``SentenceTransformer`` model is loaded on the first call to
    :meth:`embed` or :meth:`embed_batch` and reused for all subsequent
    calls.

    All ``model.encode()`` calls are offloaded to a thread via
    ``asyncio.to_thread()`` so the FastAPI event loop is never blocked.

    Usage::

        provider = SentenceTransformerEmbeddingProvider()
        vec = await provider.embed("Hello world")
        batch = await provider.embed_batch(["Hello", "World"])
    """

    def __init__(self, config: EmbeddingConfig | None = None) -> None:
        self._config = config or EmbeddingConfig()
        self._model = None          # loaded lazily
        self._lock = threading.Lock()

    # ── IEmbeddingProvider properties ────────────────────────────────────────

    @property
    def model_name(self) -> str:
        return self._config.model_name

    @property
    def embedding_dimension(self) -> int:
        """Return the output dimension.

        Uses the known dimension for ``all-MiniLM-L6-v2`` before the model
        is loaded; for other models it loads the model first.
        """
        if self._config.model_name == _DEFAULT_MODEL:
            return _DEFAULT_DIMENSION
        # For non-default models we need the actual model
        return self._get_model().get_sentence_embedding_dimension()

    # ── IEmbeddingProvider methods ────────────────────────────────────────────

    async def embed(self, text: str) -> EmbeddingVector:
        """Embed a single *text* string.

        Args:
            text: The input text.  Must not be blank.

        Returns:
            :class:`~app.interfaces.core.EmbeddingVector`.

        Raises:
            :class:`~app.interfaces.core.EmbeddingError`: On model failure.
            ValueError: When *text* is blank.
        """
        if not text or not text.strip():
            raise ValueError("embed() received blank text.")
        results = await self.embed_batch([text])
        return results[0]

    async def embed_batch(self, texts: list[str]) -> list[EmbeddingVector]:
        """Embed a list of *texts* in one batch call.

        Args:
            texts: Non-empty list of strings.

        Returns:
            ``list[EmbeddingVector]`` in the same order as *texts*.

        Raises:
            :class:`~app.interfaces.core.EmbeddingError`: On model failure.
            ValueError: When *texts* is empty.
        """
        if not texts:
            raise ValueError("embed_batch() received an empty list.")

        def _encode() -> list[list[float]]:
            model = self._get_model()
            try:
                embeddings = model.encode(
                    texts,
                    show_progress_bar=self._config.show_progress_bar,
                    normalize_embeddings=self._config.normalize_embeddings,
                )
                return [emb.tolist() for emb in embeddings]
            except Exception as exc:
                raise EmbeddingError(
                    message=f"SentenceTransformer encode failed: {exc}",
                    model_name=self._config.model_name,
                    retryable=False,
                ) from exc

        try:
            raw_vectors = await asyncio.to_thread(_encode)
        except EmbeddingError:
            raise
        except Exception as exc:
            raise EmbeddingError(
                message=f"Thread dispatch failed: {exc}",
                model_name=self._config.model_name,
                retryable=True,
            ) from exc

        return [
            EmbeddingVector(
                values=vec,
                source_text=text,
                model_name=self._config.model_name,
            )
            for vec, text in zip(raw_vectors, texts, strict=True)
        ]

    # ── Private helpers ───────────────────────────────────────────────────────

    def _get_model(self):
        """Return the cached SentenceTransformer, loading it on first call.

        Thread-safe: uses a ``threading.Lock`` so concurrent async tasks
        can call ``_get_model()`` without double-loading the model.
        """
        if self._model is not None:
            return self._model

        with self._lock:
            # Double-checked locking — another thread may have loaded
            # the model while we were waiting for the lock.
            if self._model is not None:
                return self._model

            logger.info(
                "Loading SentenceTransformer model %r on device %r",
                self._config.model_name,
                self._config.device,
            )
            try:
                from sentence_transformers import SentenceTransformer  # lazy import

                model = SentenceTransformer(
                    self._config.model_name,
                    device=self._config.device,
                )
                self._model = model
                logger.info(
                    "SentenceTransformer model %r loaded (dim=%d)",
                    self._config.model_name,
                    model.get_sentence_embedding_dimension(),
                )
            except Exception as exc:
                raise EmbeddingError(
                    message=f"Failed to load model {self._config.model_name!r}: {exc}",
                    model_name=self._config.model_name,
                    retryable=False,
                ) from exc

        return self._model
