"""Unit tests for app.embedding.provider — EmbeddingConfig and
SentenceTransformerEmbeddingProvider.

sentence-transformers is NOT loaded in these tests.  Every test that
would trigger model.encode() patches the _get_model() method so no
heavy ML library needs to be present.

No production credentials required.
"""

from __future__ import annotations

import asyncio
from unittest.mock import MagicMock, patch

import pytest

from app.embedding.provider import EmbeddingConfig, SentenceTransformerEmbeddingProvider
from app.interfaces.core import EmbeddingError, EmbeddingVector


# ── Helpers ───────────────────────────────────────────────────────────────────


def _make_provider(model_name: str = "all-MiniLM-L6-v2") -> SentenceTransformerEmbeddingProvider:
    return SentenceTransformerEmbeddingProvider(EmbeddingConfig(model_name=model_name))


def _fake_model(dim: int = 4, vectors: list[list[float]] | None = None):
    """Return a mock SentenceTransformer whose encode() returns fixed vectors."""
    import numpy as np

    mock = MagicMock()
    mock.get_sentence_embedding_dimension.return_value = dim

    def _encode(texts, **kwargs):
        if vectors is not None:
            return [np.array(v) for v in vectors[: len(texts)]]
        return [np.zeros(dim) for _ in texts]

    mock.encode.side_effect = _encode
    return mock


# ── EmbeddingConfig ────────────────────────────────────────────────────────────


class TestEmbeddingConfig:
    def test_default_model_name(self):
        assert EmbeddingConfig().model_name == "all-MiniLM-L6-v2"

    def test_default_device_cpu(self):
        assert EmbeddingConfig().device == "cpu"

    def test_default_no_progress_bar(self):
        assert EmbeddingConfig().show_progress_bar is False

    def test_default_normalize_embeddings(self):
        assert EmbeddingConfig().normalize_embeddings is True

    def test_custom_model_name(self):
        cfg = EmbeddingConfig(model_name="paraphrase-MiniLM-L3-v2")
        assert cfg.model_name == "paraphrase-MiniLM-L3-v2"

    def test_custom_device(self):
        cfg = EmbeddingConfig(device="cuda")
        assert cfg.device == "cuda"


# ── Properties ────────────────────────────────────────────────────────────────


class TestProviderProperties:
    def test_model_name_from_config(self):
        p = _make_provider("my-model")
        assert p.model_name == "my-model"

    def test_embedding_dimension_default_model_no_load(self):
        # Default model (all-MiniLM-L6-v2) returns 384 without loading the model.
        p = _make_provider()
        assert p.embedding_dimension == 384
        # Model was NOT loaded
        assert p._model is None

    def test_embedding_dimension_other_model_loads_model(self):
        p = _make_provider("other-model")
        mock = _fake_model(dim=256)
        p._model = mock
        assert p.embedding_dimension == 256

    def test_is_not_abstract(self):
        from app.interfaces.core import IEmbeddingProvider

        assert isinstance(_make_provider(), IEmbeddingProvider)


# ── embed() ───────────────────────────────────────────────────────────────────


class TestEmbed:
    @pytest.mark.asyncio
    async def test_embed_returns_embedding_vector(self):
        p = _make_provider()
        p._model = _fake_model(dim=4, vectors=[[0.1, 0.2, 0.3, 0.4]])
        result = await p.embed("Hello world")
        assert isinstance(result, EmbeddingVector)
        assert result.dimensions == 4

    @pytest.mark.asyncio
    async def test_embed_sets_source_text(self):
        p = _make_provider()
        p._model = _fake_model(dim=4, vectors=[[0.1, 0.2, 0.3, 0.4]])
        result = await p.embed("my text")
        assert result.source_text == "my text"

    @pytest.mark.asyncio
    async def test_embed_sets_model_name(self):
        p = _make_provider("test-model")
        p._model = _fake_model(dim=4, vectors=[[0.1, 0.2, 0.3, 0.4]])
        result = await p.embed("hi")
        assert result.model_name == "test-model"

    @pytest.mark.asyncio
    async def test_embed_blank_text_raises(self):
        p = _make_provider()
        with pytest.raises(ValueError, match="blank"):
            await p.embed("   ")

    @pytest.mark.asyncio
    async def test_embed_empty_string_raises(self):
        p = _make_provider()
        with pytest.raises(ValueError, match="blank"):
            await p.embed("")

    @pytest.mark.asyncio
    async def test_embed_delegates_to_embed_batch(self):
        p = _make_provider()
        p._model = _fake_model(dim=4, vectors=[[1.0, 0.0, 0.0, 0.0]])
        result = await p.embed("single text")
        assert result.values == pytest.approx([1.0, 0.0, 0.0, 0.0], abs=1e-5)


# ── embed_batch() ─────────────────────────────────────────────────────────────


class TestEmbedBatch:
    @pytest.mark.asyncio
    async def test_embed_batch_returns_one_vector_per_input(self):
        p = _make_provider()
        vecs = [[0.1, 0.2], [0.3, 0.4], [0.5, 0.6]]
        p._model = _fake_model(dim=2, vectors=vecs)
        results = await p.embed_batch(["a", "b", "c"])
        assert len(results) == 3
        assert all(isinstance(r, EmbeddingVector) for r in results)

    @pytest.mark.asyncio
    async def test_embed_batch_preserves_order(self):
        p = _make_provider()
        vecs = [[1.0, 0.0], [0.0, 1.0]]
        p._model = _fake_model(dim=2, vectors=vecs)
        results = await p.embed_batch(["first", "second"])
        assert results[0].values == pytest.approx([1.0, 0.0], abs=1e-5)
        assert results[1].values == pytest.approx([0.0, 1.0], abs=1e-5)

    @pytest.mark.asyncio
    async def test_embed_batch_empty_list_raises(self):
        p = _make_provider()
        with pytest.raises(ValueError, match="empty"):
            await p.embed_batch([])

    @pytest.mark.asyncio
    async def test_embed_batch_source_text_set_per_item(self):
        p = _make_provider()
        p._model = _fake_model(dim=2)
        results = await p.embed_batch(["foo", "bar"])
        assert results[0].source_text == "foo"
        assert results[1].source_text == "bar"

    @pytest.mark.asyncio
    async def test_embed_batch_model_error_raises_embedding_error(self):
        p = _make_provider()
        mock = MagicMock()
        mock.encode.side_effect = RuntimeError("model crash")
        p._model = mock
        with pytest.raises(EmbeddingError) as exc_info:
            await p.embed_batch(["text"])
        assert exc_info.value.model_name == "all-MiniLM-L6-v2"
        assert exc_info.value.retryable is False

    @pytest.mark.asyncio
    async def test_embed_batch_single_item(self):
        p = _make_provider()
        p._model = _fake_model(dim=3, vectors=[[0.1, 0.2, 0.3]])
        results = await p.embed_batch(["only one"])
        assert len(results) == 1
        assert results[0].dimensions == 3


# ── Lazy model loading ────────────────────────────────────────────────────────


class TestLazyModelLoading:
    def test_model_not_loaded_at_construction(self):
        p = _make_provider()
        assert p._model is None

    @pytest.mark.asyncio
    async def test_model_loaded_after_first_embed(self):
        p = _make_provider()
        p._model = _fake_model()  # inject fake to avoid real loading
        await p.embed("hello")
        assert p._model is not None

    @pytest.mark.asyncio
    async def test_model_loaded_only_once(self):
        p = _make_provider()
        mock = _fake_model(dim=4)
        p._model = mock
        await p.embed("first")
        await p.embed("second")
        # encode called twice (once per embed), model loaded once
        assert mock.encode.call_count == 2

    def test_get_model_raises_embedding_error_when_import_fails(self):
        p = _make_provider()
        with patch.dict("sys.modules", {"sentence_transformers": None}):
            with pytest.raises(EmbeddingError) as exc_info:
                p._get_model()
        assert exc_info.value.retryable is False


# ── Thread safety ─────────────────────────────────────────────────────────────


class TestThreadSafety:
    @pytest.mark.asyncio
    async def test_concurrent_embeds_all_succeed(self):
        """Multiple concurrent embed() calls should not double-load the model."""
        p = _make_provider()
        p._model = _fake_model(dim=4)

        results = await asyncio.gather(
            p.embed("text-1"),
            p.embed("text-2"),
            p.embed("text-3"),
        )
        assert len(results) == 3
        assert all(isinstance(r, EmbeddingVector) for r in results)
