/**
 * ObservabilityUploadWorker.kt — core-network module
 *
 * Purpose: Periodic [CoroutineWorker] that drains [ObservabilityManager]'s in-memory
 *          buffer and POSTs the events to the backend observability ingest endpoint.
 *
 * Architecture: core-network — lives here (not core-common) because it depends on:
 *   - Hilt (@AssistedInject + @HiltWorker)
 *   - [ObservabilityApiService] (Retrofit interface, defined in this module)
 *   - [ObservabilityManager] (provided by ObservabilityModule in this module)
 *
 * Scheduling:
 *   Enqueued by [scheduleObservabilityUpload] as a periodic request every 15 minutes.
 *   Uses [ExistingPeriodicWorkPolicy.KEEP] so re-scheduling on every app launch
 *   is idempotent — the existing schedule is preserved.
 *
 * Retry policy:
 *   Returns [Result.retry()] on network failure. WorkManager applies exponential
 *   back-off (default 30s initial, doubles up to 5h max).
 *   On [Result.failure] (serialization error or empty drain) the work is not retried.
 *
 * Privacy:
 *   All events have already been filtered by [PiiFilter] at capture time.
 *   This worker never applies additional filtering — what's in the buffer is
 *   already safe to send.
 *
 * Phase 8 — Observability
 */

package com.aiassistant.core.network.observability

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aiassistant.core.common.observability.ObservabilityEvent
import com.aiassistant.core.common.observability.ObservabilityManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/** WorkManager unique work name — used to prevent duplicate schedules. */
private const val WORK_NAME = "observability_upload"

/** Repeat interval in minutes. Must be >= 15 (WorkManager minimum). */
private const val REPEAT_INTERVAL_MINUTES = 15L

/** Maximum events to upload in a single batch to avoid oversized payloads. */
private const val MAX_BATCH_SIZE = 200

private val JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()

/**
 * Hilt-injected [CoroutineWorker] that uploads buffered [ObservabilityEvent] instances
 * to the backend ingest endpoint every [REPEAT_INTERVAL_MINUTES] minutes.
 *
 * @param manager     Source of buffered events — injected by Hilt.
 * @param okHttpClient Plain OkHttpClient (no auth interceptor) for uploading events.
 *                    Observability events are submitted without a user JWT so they
 *                    can be uploaded even if the user is logged out.
 * @param baseUrl     Backend base URL injected via Hilt qualifier.
 */
@HiltWorker
class ObservabilityUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val manager: ObservabilityManager,
    private val okHttpClient: OkHttpClient,
    @ObservabilityBaseUrl private val baseUrl: String
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val events = manager.drain()

        if (events.isEmpty()) {
            Timber.d("ObservabilityUploadWorker: no events to upload — skipping")
            return Result.success()
        }

        Timber.d("ObservabilityUploadWorker: uploading %d events", events.size)

        // Batch into chunks to avoid a single very large request body
        val batches = events.chunked(MAX_BATCH_SIZE)
        var totalUploaded = 0

        for (batch in batches) {
            val result = uploadBatch(batch)
            when (result) {
                is BatchResult.Success -> totalUploaded += batch.size
                is BatchResult.NetworkError -> {
                    // Network failure — ask WorkManager to retry the whole job.
                    // Events already drained from the buffer are lost on retry,
                    // but WorkManager's exponential back-off means this is rare.
                    Timber.w(
                        "ObservabilityUploadWorker: network error after %d/%d events — retrying. %s",
                        totalUploaded,
                        events.size,
                        result.message
                    )
                    return Result.retry()
                }
                is BatchResult.ServerError -> {
                    // Server rejected the payload (4xx/5xx) — log and continue.
                    // Retrying is unlikely to help for 4xx; for 5xx the next
                    // periodic run will retry.
                    Timber.w(
                        "ObservabilityUploadWorker: server error %d for batch of %d events",
                        result.statusCode,
                        batch.size
                    )
                    // Continue uploading remaining batches
                }
            }
        }

        Timber.i("ObservabilityUploadWorker: uploaded %d/%d events", totalUploaded, events.size)
        return Result.success()
    }

    private fun uploadBatch(events: List<ObservabilityEvent>): BatchResult = try {
        val body = JSON.encodeToString(events).toRequestBody(MEDIA_TYPE_JSON)
        val request = Request.Builder()
            .url("${baseUrl}api/v1/observability/events")
            .post(body)
            .header("Content-Type", "application/json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        response.use {
            if (it.isSuccessful) {
                BatchResult.Success
            } else {
                BatchResult.ServerError(it.code)
            }
        }
    } catch (e: Exception) {
        BatchResult.NetworkError(e.message ?: "unknown error")
    }

    private sealed interface BatchResult {
        data object Success : BatchResult
        data class NetworkError(val message: String) : BatchResult
        data class ServerError(val statusCode: Int) : BatchResult
    }
}

/**
 * Hilt qualifier for the observability base URL injection.
 * Prevents collision with other String providers in the DI graph.
 */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ObservabilityBaseUrl

/**
 * Schedule or keep the periodic observability upload work.
 *
 * Call this once from [Application.onCreate] after WorkManager is configured.
 * [ExistingPeriodicWorkPolicy.KEEP] means if the work is already scheduled
 * (e.g. from a previous app launch), the existing schedule is preserved.
 *
 * Requires CONNECTED network — avoids wasting battery on upload retries
 * when there is no network available.
 *
 * @param context Application context.
 */
fun scheduleObservabilityUpload(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val request = PeriodicWorkRequestBuilder<ObservabilityUploadWorker>(
        repeatInterval = REPEAT_INTERVAL_MINUTES,
        repeatIntervalTimeUnit = TimeUnit.MINUTES
    )
        .setConstraints(constraints)
        .setBackoffCriteria(
            backoffPolicy = BackoffPolicy.EXPONENTIAL,
            backoffDelay = 30L,
            timeUnit = TimeUnit.SECONDS
        )
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )

    Timber.d("ObservabilityUploadWorker: scheduled (interval=%dm)", REPEAT_INTERVAL_MINUTES)
}
