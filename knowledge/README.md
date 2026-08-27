# DevOps Knowledge Base

This folder contains the operational knowledge that is ingested into ChromaDB
and made available to the AI DevOps Assistant via RAG.

## Structure

```
knowledge/
├── runbooks/       — Step-by-step operational procedures
├── incidents/      — Historical incident reports with root cause and resolution
├── architecture/   — System design and component documentation
└── deployment/     — Deployment procedures and environment configuration
```

## How documents are loaded

Run `backend/scripts/seed_knowledge.py` to ingest all documents into the
`devops_knowledge` ChromaDB collection. The seeding script is idempotent —
re-running it updates existing chunks rather than creating duplicates.

## Adding new documents

1. Place a `.md`, `.txt`, or `.pdf` file in the appropriate subfolder.
2. Re-run `python backend/scripts/seed_knowledge.py`.
3. The AI assistant will be able to answer questions about the new content
   within seconds of the script completing.

## Collection name

All documents in this folder are indexed into a shared collection called
`devops_knowledge` (not per-user). The AI DevOps Assistant queries this
collection directly when answering infrastructure questions.
