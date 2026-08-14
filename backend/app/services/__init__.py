"""Services package — business logic layer.

All services accept dependencies injected by the caller (typically FastAPI
dependency injection or direct instantiation in tests). They orchestrate
business logic, coordinate between repositories, and enforce invariants.
"""

from app.services.ai_orchestrator import (
    AIOrchestrator,
    CompletionResult,
    LLMProvider,
    PromptContext,
)
from app.services.auth_service import (
    issue_tokens_for_user,
    logout_user,
    refresh_tokens,
)
from app.services.llm_clients import (
    BaseLLMClient,
    ClaudeClient,
    GeminiClient,
    LlamaClient,
    MistralClient,
    OllamaClient,
    OpenAIClient,
)
from app.services.memory_service import MemoryEntry, MemoryService

__all__ = [
    # AI Orchestrator
    "AIOrchestrator",
    "LLMProvider",
    "PromptContext",
    "CompletionResult",
    # Auth
    "issue_tokens_for_user",
    "refresh_tokens",
    "logout_user",
    # LLM Clients
    "BaseLLMClient",
    "OpenAIClient",
    "GeminiClient",
    "ClaudeClient",
    "OllamaClient",
    "LlamaClient",
    "MistralClient",
    # Memory
    "MemoryService",
    "MemoryEntry",
]
