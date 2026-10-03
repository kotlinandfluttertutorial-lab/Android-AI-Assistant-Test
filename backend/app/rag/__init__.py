"""RAG retrieval and question-answering pipeline.

Public surface
--------------
ContextBuilder   – assembles retrieved chunks into a numbered context block
                   and formats source citations.
RetrievalConfig  – configurable parameters (top_k, similarity_threshold, …).
VectorRetriever  – IRetriever backed by IEmbeddingProvider + IVectorStore.
RAGPipeline      – façade: retrieve → build context → LLM → answer + sources.

Usage::

    from app.rag import RAGPipeline, RetrievalConfig

    pipeline = RAGPipeline(
        retriever=VectorRetriever(embedding_provider, vector_store),
        llm_service=get_llm_service(),
        config=RetrievalConfig(top_k=5, min_similarity=0.4),
    )
    result = await pipeline.ask(user_id="...", question="What is X?")
    print(result.answer)
    for src in result.sources:
        print(src["document_name"], src["page_number"])
"""

from app.rag.context_builder import ContextBuilder
from app.rag.pipeline import RAGAnswer, RAGPipeline
from app.rag.retriever import RetrievalConfig, VectorRetriever

__all__ = [
    "ContextBuilder",
    "RAGAnswer",
    "RAGPipeline",
    "RetrievalConfig",
    "VectorRetriever",
]
