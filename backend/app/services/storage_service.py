"""Storage service — abstract backend for document file storage.

Provides a single interface (``StorageService``) with two concrete adapters:

  ``GCSStorageBackend``   — Google Cloud Storage (production on Cloud Run).
                            Uses Application Default Credentials (ADC); no key
                            file needed when the Cloud Run service account has
                            ``roles/storage.objectAdmin`` on the bucket.

  ``MinioStorageBackend`` — MinIO S3-compatible server (local Docker Compose).
                            Uses ``MINIO_ENDPOINT`` / ``MINIO_ACCESS_KEY`` /
                            ``MINIO_SECRET_KEY`` from settings.

Which backend is active is controlled by the ``STORAGE_BACKEND`` env var:
  STORAGE_BACKEND=gcs    → GCSStorageBackend   (Cloud Run)
  STORAGE_BACKEND=minio  → MinioStorageBackend  (local / default)

All I/O is wrapped in ``asyncio.to_thread()`` so blocking SDK calls do not
stall the FastAPI event loop.

Usage::

    from app.services.storage_service import storage_service

    key  = await storage_service.upload(file_bytes, filename, user_id, doc_id)
    data = await storage_service.download(key)
    await storage_service.delete(key)

Architecture layer : Service
Requirements       : 4.1, 4.2, 4.4 (document storage)
"""

from __future__ import annotations

import asyncio
import io
import logging
import os
import uuid
from abc import ABC, abstractmethod

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Helper — derive MIME type from file extension
# ---------------------------------------------------------------------------

def _mime_from_extension(ext: str) -> str:
    mapping = {
        ".pdf":  "application/pdf",
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".txt":  "text/plain",
        ".md":   "text/markdown",
    }
    return mapping.get(ext.lower(), "application/octet-stream")


# ---------------------------------------------------------------------------
# Abstract base
# ---------------------------------------------------------------------------

class StorageBackend(ABC):
    """Protocol implemented by every storage backend."""

    @abstractmethod
    async def upload(
        self,
        file_bytes: bytes,
        filename: str,
        user_id: str,
        document_id: str,
    ) -> str:
        """Store ``file_bytes`` and return the object key (path)."""

    @abstractmethod
    async def download(self, object_key: str) -> bytes:
        """Return the raw bytes stored under ``object_key``."""

    @abstractmethod
    async def delete(self, object_key: str) -> None:
        """Delete the object identified by ``object_key`` (best-effort)."""


# ---------------------------------------------------------------------------
# GCS backend — production (Cloud Run + ADC)
# ---------------------------------------------------------------------------

class GCSStorageBackend(StorageBackend):
    """Google Cloud Storage backend.

    Authentication uses Application Default Credentials (ADC).  On Cloud Run,
    ADC resolves to the service account attached to the revision — no key file
    needed.  Locally, run ``gcloud auth application-default login`` once.

    The Cloud Run service account requires:
        roles/storage.objectAdmin  on the GCS bucket
        (or the project-level equivalent for dev/test)

    Object key format: ``{user_id}/{document_id}/{filename}``
    """

    def __init__(self, bucket_name: str) -> None:
        if not bucket_name:
            raise ValueError(
                "GCS_BUCKET_NAME must be set when STORAGE_BACKEND=gcs. "
                "Add it to Cloud Run env vars or Secret Manager."
            )
        self._bucket_name = bucket_name
        # Lazy client — avoids importing google-cloud-storage until first call
        self._client = None

    def _get_client(self):
        if self._client is None:
            from google.cloud import storage as gcs  # noqa: PLC0415
            self._client = gcs.Client()
        return self._client

    def _get_bucket(self):
        return self._get_client().bucket(self._bucket_name)

    async def upload(
        self,
        file_bytes: bytes,
        filename: str,
        user_id: str,
        document_id: str,
    ) -> str:
        object_key = f"{user_id}/{document_id}/{filename}"
        ext = os.path.splitext(filename)[1].lower()
        content_type = _mime_from_extension(ext)

        def _do_upload() -> None:
            bucket = self._get_bucket()
            blob = bucket.blob(object_key)
            blob.upload_from_file(
                io.BytesIO(file_bytes),
                content_type=content_type,
                size=len(file_bytes),
            )
            logger.info(
                "GCS upload complete: gs://%s/%s (%d bytes)",
                self._bucket_name,
                object_key,
                len(file_bytes),
            )

        await asyncio.to_thread(_do_upload)
        return object_key

    async def download(self, object_key: str) -> bytes:
        def _do_download() -> bytes:
            bucket = self._get_bucket()
            blob = bucket.blob(object_key)
            return blob.download_as_bytes()

        return await asyncio.to_thread(_do_download)

    async def delete(self, object_key: str) -> None:
        def _do_delete() -> None:
            try:
                bucket = self._get_bucket()
                blob = bucket.blob(object_key)
                blob.delete()
                logger.info("GCS delete: gs://%s/%s", self._bucket_name, object_key)
            except Exception as exc:
                # NotFound is acceptable — object may already be gone
                logger.warning("GCS delete failed for '%s': %s", object_key, exc)

        try:
            await asyncio.to_thread(_do_delete)
        except Exception as exc:
            logger.warning("GCS delete thread error for '%s': %s", object_key, exc)


# ---------------------------------------------------------------------------
# MinIO backend — local development (Docker Compose)
# ---------------------------------------------------------------------------

class MinioStorageBackend(StorageBackend):
    """MinIO S3-compatible backend for local Docker Compose development.

    Reads ``MINIO_ENDPOINT``, ``MINIO_ACCESS_KEY``, ``MINIO_SECRET_KEY``, and
    ``MINIO_BUCKET_NAME`` from application settings.  Bucket is created
    automatically on first upload if it does not exist.
    """

    def __init__(
        self,
        endpoint: str,
        access_key: str,
        secret_key: str,
        bucket_name: str,
    ) -> None:
        self._endpoint    = endpoint
        self._access_key  = access_key or None
        self._secret_key  = secret_key or None
        self._bucket_name = bucket_name

    def _get_client(self):
        from minio import Minio  # noqa: PLC0415
        return Minio(
            self._endpoint,
            access_key=self._access_key,
            secret_key=self._secret_key,
            secure=False,
        )

    def _ensure_bucket(self, client) -> None:
        from minio.error import S3Error  # noqa: PLC0415
        try:
            if not client.bucket_exists(self._bucket_name):
                client.make_bucket(self._bucket_name)
        except S3Error as exc:
            logger.warning("MinIO bucket check/create failed: %s", exc)

    async def upload(
        self,
        file_bytes: bytes,
        filename: str,
        user_id: str,
        document_id: str,
    ) -> str:
        object_key = f"{user_id}/{document_id}/{filename}"
        ext = os.path.splitext(filename)[1].lower()
        content_type = _mime_from_extension(ext)

        def _do_upload() -> None:
            client = self._get_client()
            self._ensure_bucket(client)
            client.put_object(
                self._bucket_name,
                object_key,
                io.BytesIO(file_bytes),
                length=len(file_bytes),
                content_type=content_type,
            )

        await asyncio.to_thread(_do_upload)
        return object_key

    async def download(self, object_key: str) -> bytes:
        def _do_download() -> bytes:
            client = self._get_client()
            response = client.get_object(self._bucket_name, object_key)
            try:
                return response.read()
            finally:
                response.close()
                response.release_conn()

        return await asyncio.to_thread(_do_download)

    async def delete(self, object_key: str) -> None:
        def _do_delete() -> None:
            try:
                client = self._get_client()
                client.remove_object(self._bucket_name, object_key)
            except Exception as exc:
                logger.warning("MinIO delete failed for '%s': %s", object_key, exc)

        try:
            await asyncio.to_thread(_do_delete)
        except Exception as exc:
            logger.warning("MinIO delete thread error for '%s': %s", object_key, exc)


# ---------------------------------------------------------------------------
# StorageService — thin facade used by rag_service
# ---------------------------------------------------------------------------

class StorageService:
    """Facade that delegates to whichever backend is configured.

    Backend selection is lazy (resolved on first call) so that settings are
    read after ``get_settings()`` is available and tests can override env vars
    before the first call.

    Usage::

        key  = await storage_service.upload(file_bytes, filename, user_id, doc_id)
        data = await storage_service.download(key)
        await storage_service.delete(key)
    """

    def __init__(self) -> None:
        self._backend: StorageBackend | None = None

    def _get_backend(self) -> StorageBackend:
        if self._backend is not None:
            return self._backend

        from app.config.settings import get_settings  # noqa: PLC0415
        s = get_settings()
        backend = s.STORAGE_BACKEND.lower().strip()

        if backend == "gcs":
            logger.info("Storage backend: GCS (bucket=%s)", s.GCS_BUCKET_NAME)
            self._backend = GCSStorageBackend(bucket_name=s.GCS_BUCKET_NAME)
        else:
            if backend != "minio":
                logger.warning(
                    "Unknown STORAGE_BACKEND=%r — falling back to 'minio'.", backend
                )
            logger.info(
                "Storage backend: MinIO (endpoint=%s, bucket=%s)",
                s.MINIO_ENDPOINT,
                s.MINIO_BUCKET_NAME,
            )
            self._backend = MinioStorageBackend(
                endpoint=s.MINIO_ENDPOINT,
                access_key=s.MINIO_ACCESS_KEY,
                secret_key=s.MINIO_SECRET_KEY,
                bucket_name=s.MINIO_BUCKET_NAME,
            )

        return self._backend

    # -- public API ----------------------------------------------------------

    async def upload(
        self,
        file_bytes: bytes,
        filename: str,
        user_id: str,
        document_id: str | None = None,
    ) -> str:
        """Store a file and return its object key.

        Args:
            file_bytes:  Raw file content.
            filename:    Original filename (used as the key leaf segment).
            user_id:     String UUID of the uploading user.
            document_id: String UUID of the document row; auto-generated if None.

        Returns:
            Object key string, e.g. ``"uuid1/uuid2/report.pdf"``.
        """
        if document_id is None:
            document_id = str(uuid.uuid4())
        return await self._get_backend().upload(file_bytes, filename, user_id, document_id)

    async def download(self, object_key: str) -> bytes:
        """Return the raw bytes stored under ``object_key``."""
        return await self._get_backend().download(object_key)

    async def delete(self, object_key: str) -> None:
        """Delete the object (best-effort — logs and swallows errors)."""
        await self._get_backend().delete(object_key)

    def reset(self) -> None:
        """Reset the cached backend — useful in tests that swap STORAGE_BACKEND."""
        self._backend = None


# ---------------------------------------------------------------------------
# Module-level singleton — import this everywhere
# ---------------------------------------------------------------------------

storage_service = StorageService()
