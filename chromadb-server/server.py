"""
ChromaDB server wrapper that exposes the v2 FastAPI app at module level.
The chromadb.server.fastapi module contains a FastAPI class but no module-level
`app` — this wrapper instantiates it so Uvicorn can serve it.
"""
import os
from chromadb.config import Settings, System
from chromadb.server.fastapi import FastAPI

settings = Settings(
    is_persistent=True,
    persist_directory=os.environ.get("PERSIST_DIRECTORY", "/tmp/chroma"),
    anonymized_telemetry=False,
    allow_reset=True,
)

# Instantiate the v2 FastAPI server — this registers /api/v2/* routes
_server = FastAPI(settings)

# Expose the underlying Starlette app for Uvicorn
app = _server.app
