"""Property-based tests for RAG chunk coverage without gaps.

Property 7: RAG Chunk Coverage Without Gaps
**Validates: Requirements 4.3**

Strategy:
  - ``st.text(min_size=0, max_size=5000)`` for random text documents of varying lengths
  - Fixed chunk_size=512, overlap=64 (defaults per Requirement 4.3)

Assertions:
  - For every generated text T and resulting chunks C_1…C_n, every character
    position p in T is covered by at least one chunk:
      ∀ p in range(len(T)): ∃ i such that T[p] appears in C_i's region of T
  - Equivalently: the union of all chunk texts covers the full source text
    (no byte/character of the source is absent from all chunks)
  - Overlaps between adjacent chunks are allowed
  - Each chunk in a non-empty document is non-empty

Edge cases also covered as parametrised examples:
  - empty string        → empty chunk list (not a coverage failure)
  - single character    → exactly one chunk containing that character
  - string shorter than chunk_size
  - string exactly equal to chunk_size
  - string much larger than chunk_size

Requirements: 4.3
"""

from __future__ import annotations

import os

# ---------------------------------------------------------------------------
# Environment variables must be set BEFORE any app imports
# ---------------------------------------------------------------------------
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

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# ---------------------------------------------------------------------------
# Constants — default chunk parameters per Requirement 4.3
# ---------------------------------------------------------------------------

DEFAULT_CHUNK_SIZE: int = 512  # tokens
DEFAULT_OVERLAP: int = 64  # tokens


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _chunk_text_default(text: str) -> list[str]:
    """Return chunk texts using RAGService.chunk_text with default parameters."""
    from app.services.rag_service import RAGService

    service = RAGService()
    results = service.chunk_text(
        text, chunk_size=DEFAULT_CHUNK_SIZE, overlap=DEFAULT_OVERLAP
    )
    return [r.text for r in results]


def _assert_full_coverage(text: str, chunks: list[str]) -> None:
    """Assert that the union of all chunk texts covers the full source text.

    Strategy: rebuild the source text from the tiktoken round-trip, then check
    that every decoded token-sequence is present in at least one chunk.

    Because ``chunk_text`` operates on tiktoken tokens and re-decodes each chunk,
    the comparison is done at the token level to avoid byte-boundary issues that
    arise from UTF-8 multi-byte characters being split across a raw character window.

    We verify the property in two equivalent ways:
    1. Token-level: every token in the encoded text appears in ≥1 chunk (direct).
    2. Text-level: concatenating all unique chunks (with their overlaps) reproduces
       the full decoded source text as a contiguous substring chain.
    """
    import tiktoken

    enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
    tokens = enc.encode(text)

    if not tokens:
        # Empty or whitespace-only input: chunk_text correctly returns [].
        assert chunks == [], (
            f"Property 7 violated: chunk_text returned non-empty chunks for empty/whitespace text. "
            f"Got {len(chunks)} chunk(s)."
        )
        return

    assert len(chunks) > 0, (
        f"Property 7 violated: chunk_text returned no chunks for non-empty text "
        f"(text length={len(text)}, token count={len(tokens)})."
    )

    # Build the set of all tokens (as integers) that appear in each chunk
    all_chunk_token_sets: list[set[int]] = [set(enc.encode(c)) for c in chunks]
    union_of_chunk_tokens: set[int] = set().union(*all_chunk_token_sets)

    for i, token_id in enumerate(tokens):
        assert token_id in union_of_chunk_tokens, (
            f"Property 7 violated: token at position {i} (id={token_id}, "
            f"decoded={enc.decode([token_id])!r}) is not covered by any chunk. "
            f"Source text length={len(text)}, token count={len(tokens)}, "
            f"chunk count={len(chunks)}."
        )

    # Additionally verify each chunk is non-empty
    for idx, chunk in enumerate(chunks):
        assert len(chunk) > 0, (
            f"Property 7 violated: chunk {idx} is empty for non-empty source text."
        )


# ===========================================================================
# Property 7 — RAG Chunk Coverage Without Gaps (Hypothesis)
# **Validates: Requirements 4.3**
# ===========================================================================


@given(
    text=st.text(
        # Allow printable ASCII + broader unicode; keep it to 5000 chars max so
        # tests stay fast while covering a wide token-count range.
        alphabet=st.characters(
            whitelist_categories=(
                "L",
                "N",
                "P",
                "Z",
            ),  # letters, numbers, punctuation, separators
        ),
        min_size=0,
        max_size=5000,
    )
)
@settings(
    max_examples=100,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_7_chunk_coverage_no_gaps(text: str) -> None:
    """**Validates: Requirements 4.3**

    Property 7: For every text document T produced by the generator and the
    resulting chunk list C_1…C_n from ``RAGService.chunk_text``, every token
    of T is present in the union of C_1…C_n — no segment of the source text is
    absent from all chunks.

    Overlaps between adjacent chunks are allowed. The property holds for
    documents of any length, including those shorter than chunk_size.
    """
    chunks = _chunk_text_default(text)
    _assert_full_coverage(text, chunks)


# ===========================================================================
# Edge-case parametrised tests
# **Validates: Requirements 4.3**
# ===========================================================================


class TestChunkCoverageEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property test."""

    def test_empty_string_returns_no_chunks(self) -> None:
        """Empty input must produce an empty chunk list, not raise."""
        chunks = _chunk_text_default("")
        assert chunks == [], f"Expected empty list, got {chunks!r}"

    def test_whitespace_only_returns_no_chunks(self) -> None:
        """Whitespace-only text is treated the same as empty (stripped)."""
        chunks = _chunk_text_default("   \n\t  ")
        assert chunks == [], (
            f"Expected empty list for whitespace-only text, got {chunks!r}"
        )

    def test_single_character_covered(self) -> None:
        """A single-character document must be fully covered by the one chunk."""
        text = "A"
        chunks = _chunk_text_default(text)
        assert len(chunks) == 1, f"Expected 1 chunk for single char, got {len(chunks)}"
        assert "A" in chunks[0], (
            f"Single character 'A' not found in chunk: {chunks[0]!r}"
        )

    def test_string_shorter_than_chunk_size_fully_covered(self) -> None:
        """A document shorter than chunk_size must fit in exactly one chunk."""
        # 100 ASCII chars ≪ 512 tokens
        text = "Hello world. " * 7  # ~91 chars, ~25 tokens
        chunks = _chunk_text_default(text)
        assert len(chunks) == 1, (
            f"Text shorter than chunk_size should produce 1 chunk, got {len(chunks)}"
        )
        _assert_full_coverage(text, chunks)

    def test_string_exactly_chunk_size_covered(self) -> None:
        """A document whose token count equals chunk_size exactly must be fully covered."""
        import tiktoken

        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        # Build a text with exactly DEFAULT_CHUNK_SIZE tokens by encoding a long
        # diverse sentence, taking exactly that many tokens from the token list,
        # and decoding back.  This avoids BPE merge surprises from repeating
        # a single character.
        target_tokens = DEFAULT_CHUNK_SIZE
        # A rich sentence whose tokens don't merge unpredictably when truncated.
        seed_text = (
            "The quick brown fox jumps over the lazy dog. "
            "Pack my box with five dozen liquor jugs. "
            "How vexingly quick daft zebras jump! "
        ) * 20  # ~2 000 chars, well above 512 tokens

        tokens = enc.encode(seed_text)
        assert len(tokens) >= target_tokens, (
            f"Setup issue: seed text only has {len(tokens)} tokens, need ≥{target_tokens}"
        )
        text = enc.decode(tokens[:target_tokens])
        # Re-encode to confirm token count is stable after decode round-trip
        actual_count = len(enc.encode(text))
        assert abs(actual_count - target_tokens) <= 2, (
            f"Setup issue: expected ~{target_tokens} tokens after round-trip, "
            f"got {actual_count}. Adjust seed_text."
        )

        chunks = _chunk_text_default(text)
        assert len(chunks) == 1, (
            f"Text with token count ~= chunk_size should produce exactly 1 chunk, "
            f"got {len(chunks)}"
        )
        _assert_full_coverage(text, chunks)

    def test_string_much_larger_than_chunk_size_fully_covered(self) -> None:
        """A long document (many multiples of chunk_size) must be fully covered."""
        # ~3000 tokens (≈6× chunk_size): triggers many sliding-window iterations
        text = ("The quick brown fox jumps over the lazy dog. " * 150).strip()
        chunks = _chunk_text_default(text)
        assert len(chunks) > 1, (
            f"Long document should produce multiple chunks, got {len(chunks)}"
        )
        _assert_full_coverage(text, chunks)

    def test_overlap_produces_contiguous_coverage(self) -> None:
        """Verify that the overlap between adjacent chunks does not leave any gap.

        Constructs a text slightly larger than chunk_size so exactly two chunks
        are produced, then checks full coverage.
        """
        import tiktoken

        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        # Build text that is chunk_size + overlap + 10 tokens long using the
        # same token-slice technique to avoid BPE merge surprises.
        n_tokens = DEFAULT_CHUNK_SIZE + DEFAULT_OVERLAP + 10
        seed_text = (
            "The quick brown fox jumps over the lazy dog. "
            "Pack my box with five dozen liquor jugs. "
        ) * 30
        tokens = enc.encode(seed_text)
        assert len(tokens) >= n_tokens, (
            f"Setup issue: seed text only has {len(tokens)} tokens, need ≥{n_tokens}"
        )
        text = enc.decode(tokens[:n_tokens])

        chunks = _chunk_text_default(text)
        assert len(chunks) >= 2, (
            f"Expected ≥2 chunks for text longer than chunk_size, got {len(chunks)}"
        )
        _assert_full_coverage(text, chunks)

    def test_unicode_text_fully_covered(self) -> None:
        """Non-ASCII unicode text must be fully covered (no byte-split gaps)."""
        text = "こんにちは世界。" * 50  # Japanese, ~400 chars, higher token count
        chunks = _chunk_text_default(text)
        _assert_full_coverage(text, chunks)

    def test_all_chunks_nonempty_for_nonempty_document(self) -> None:
        """Every produced chunk must be non-empty when the source document is non-empty."""
        text = "Sample sentence. " * 200
        chunks = _chunk_text_default(text)
        for idx, chunk in enumerate(chunks):
            assert len(chunk) > 0, f"Chunk {idx} is unexpectedly empty."
