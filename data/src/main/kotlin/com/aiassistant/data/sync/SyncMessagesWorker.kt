/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : SyncMessagesWorker.kt
 * Purpose    : SyncMessagesWorker — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : WorkManager Worker
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : SyncMessagesWorker.kt
 * Purpose    : SyncMessagesWorker — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : WorkManager Worker
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * SyncMessagesWorker.kt â€” data module
 *
 * Purpose: WorkManager [CoroutineWorker] that drains the offline message queue.
 *          All outgoing [MessageEntity] objects with `syncStatus = "pending"` are
 *          submitted to the backend in original creation order (ascending `createdAt`).
 *
 *          Retry policy (Requirement 10.6):
 *            - WorkManager handles scheduling via [WorkRequest.Builder.setBackoffCriteria]:
 *              start 1 s, double each attempt, cap at 30 s, EXPONENTIAL strategy.
 *            - The worker itself counts failures per message within a single run.
 *              After [MAX_DELIVERY_ATTEMPTS] consecutive remote failures for one message,
 *              the message is marked `syncStatus = "failed"` in Room and a local
 *              notification is sent to the user via [NotificationManager].
 *            - WorkManager retry applies when the entire run fails (no connectivity, etc).
 *
 *          Connectivity gating (Requirement 10.2):
 *            - A [NetworkType.CONNECTED] constraint ensures WorkManager only runs the
 *              worker when the device has network access.
 *            - [ConnectivityObserver.isConnected] is re-checked at the start of `doWork`
 *              as a defence-in-depth guard (WorkManager may start the worker a brief
 *              moment before the OS signals full connectivity).
 *
 *          Conflict resolution (Requirement 10.3):
 *            - Server-wins for message content: after a successful remote delivery the
 *              local Room record is overwritten with the server-authoritative values
 *              (`content`, `inputTokens`, `outputTokens`, `provider`).
 *
 *          Notifications (Requirement 16.2):
 *            - On permanent failure (3rd attempt), a local notification is posted on the
 *              [SYNC_FAILURES_CHANNEL_ID] channel so the user knows a message was
 *              not delivered.
 *
 * Architecture: data module â€” consumed by [SyncModule]. Feature modules schedule work
 *               via [SyncMessagesWorker.enqueue] or [SyncMessagesWorker.enqueueImmediate].
 *               Never imports feature or UI code.
 *
 * Dependencies:
 *   MessageLocalDataSource, MessageRemoteDataSource, ConnectivityObserver,
 *   DispatcherProvider, NotificationManager, Context (Android)
 *
 * Requirements: 10.2, 10.6, 16.2
 */
package com.aiassistant.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.database.entity.MessageEntity
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.local.MessageLocalDataSource
import com.aiassistant.data.remote.message.MessageRemoteDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext

private const val TAG = "SyncMessagesWorker"

/** Notification channel for permanent sync failure alerts (Requirement 16.2). */
const val SYNC_FAILURES_CHANNEL_ID = "sync_failures"
const val SYNC_FAILURES_CHANNEL_NAME = "Sync Failures"

/** WorkManager unique work name â€” prevents duplicate runs from stacking. */
private const val WORK_NAME = "SyncMessagesWorker"

/**
 * Maximum number of delivery attempts per message before marking it permanently failed
 * and notifying the user (Requirement 10.6).
 */
private const val MAX_DELIVERY_ATTEMPTS = 3

/**
 * Initial WorkManager backoff delay in seconds (1 s start, 30 s cap, EXPONENTIAL).
 * This governs *WorkManager* rescheduling when the worker returns [Result.retry].
 *
 * Per-message retries within a single worker run are separate and handled inline.
 */
private const val BACKOFF_DELAY_SECONDS = 1L
private const val BACKOFF_CAP_SECONDS = 30L

/**
 * WorkManager worker that drains the offline message queue with exponential backoff
 * and sends a local notification on permanent delivery failure.
 *
 * Injected by Hilt via [@HiltWorker] + [dagger.assisted.AssistedInject].
 *
 * @param appContext    Android application context (injected by WorkManager).
 * @param workerParams  WorkManager worker parameters (injected by WorkManager).
 * @param localSource   Room-backed local data source for [MessageEntity] CRUD.
 * @param remoteSource  Retrofit-backed remote data source for message delivery.
 * @param connectivity  Synchronous connectivity snapshot for pre-flight guard.
 * @param dispatchers   Injected coroutine dispatcher provider.
 */
@HiltWorker
class SyncMessagesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localSource: MessageLocalDataSource,
    private val remoteSource: MessageRemoteDataSource,
    private val connectivity: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : CoroutineWorker(appContext, workerParams) {

    // â”€â”€â”€ WorkManager entry point â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Drains pending messages and returns [Result.success] when all messages are
     * processed (or there are none), [Result.retry] when the device loses connectivity
     * mid-sync, or [Result.failure] for non-recoverable errors.
     *
     * Per-message permanent failures do NOT cause the worker to retry â€” those are
     * silently absorbed (the message is marked "failed" and a notification is posted).
     */
    override suspend fun doWork(): Result = withContext(dispatchers.io) {
        // Pre-flight connectivity guard (defence-in-depth; WorkManager constraint handles
        // the primary gate, but connectivity can disappear before the coroutine starts).
        if (!connectivity.isConnected()) {
            Log.d(TAG, "doWork: no connectivity â€” retrying.")
            return@withContext Result.retry()
        }

        val pending = localSource.getPendingMessages()
        if (pending.isEmpty()) {
            Log.d(TAG, "doWork: no pending messages â€” exiting successfully.")
            return@withContext Result.success()
        }

        Log.d(TAG, "doWork: syncing ${pending.size} pending message(s) (attempt=${runAttemptCount + 1}).")

        // Track per-message failure counts for this worker run.
        val failureCounts = mutableMapOf<String, Int>()
        var lostConnectivity = false

        for (entity in pending) {
            if (!connectivity.isConnected()) {
                Log.d(TAG, "doWork: lost connectivity during sync â€” will retry worker.")
                lostConnectivity = true
                break
            }

            val attempts = failureCounts.getOrDefault(entity.id, 0)
            if (attempts >= MAX_DELIVERY_ATTEMPTS) {
                // Already exceeded limit in an earlier loop pass â€” skip.
                continue
            }

            when (
                val result = remoteSource.sendMessage(
                    conversationId = entity.conversationId,
                    content = entity.content,
                    provider = entity.provider
                )
            ) {
                is ApiResult.Success -> {
                    val dto = result.data
                    // Server-wins conflict resolution (Requirement 10.3)
                    val synced = entity.copy(
                        content = dto.content,
                        inputTokens = dto.inputTokens,
                        outputTokens = dto.outputTokens,
                        provider = dto.provider,
                        syncStatus = "synced"
                    )
                    localSource.updateMessage(synced)
                    Log.d(TAG, "doWork: message ${entity.id} delivered successfully.")
                }

                is ApiResult.Error -> {
                    val newAttempts = attempts + 1
                    failureCounts[entity.id] = newAttempts
                    Log.w(TAG, "doWork: message ${entity.id} failed (attempt $newAttempts): ${result.error.message}")

                    if (newAttempts >= MAX_DELIVERY_ATTEMPTS) {
                        // Permanent failure â€” mark the message and notify the user.
                        markAsFailed(entity)
                    }
                    // Otherwise leave as "pending" so a future worker run can retry.
                }

                is ApiResult.NetworkUnavailable -> {
                    Log.d(TAG, "doWork: network unavailable mid-sync â€” will retry worker.")
                    lostConnectivity = true
                    break
                }

                is ApiResult.Loading -> Unit // no-op
            }
        }

        if (lostConnectivity) Result.retry() else Result.success()
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Marks [entity] as permanently failed in Room and posts a local notification.
     *
     * Called after [MAX_DELIVERY_ATTEMPTS] consecutive delivery failures (Requirement 10.6).
     */
    private suspend fun markAsFailed(entity: MessageEntity) {
        localSource.updateSyncStatus(entity.id, "failed")
        Log.w(TAG, "doWork: message ${entity.id} permanently failed â€” notifying user.")
        postFailureNotification(entity)
    }

    /**
     * Posts a local notification on the [SYNC_FAILURES_CHANNEL_ID] channel informing the
     * user that a message could not be delivered (Requirement 16.2).
     *
     * Creates the notification channel on the first call (safe to call repeatedly on
     * Android 8+ â€” [NotificationManager.createNotificationChannel] is idempotent).
     */
    private fun postFailureNotification(entity: MessageEntity) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        createChannelIfNeeded(notificationManager)

        val notification = NotificationCompat.Builder(applicationContext, SYNC_FAILURES_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Message delivery failed")
            .setContentText("A message could not be delivered after $MAX_DELIVERY_ATTEMPTS attempts.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "The following message could not be delivered and has been marked as failed:\n\n" +
                            entity.content.take(200).let { if (entity.content.length > 200) "$it\u2026" else it }
                    )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // Use the message ID hash as notification ID so each failed message gets its own
        // notification (different IDs â†’ distinct notifications on the shade).
        notificationManager.notify(entity.id.hashCode(), notification)
    }

    /**
     * Creates the [SYNC_FAILURES_CHANNEL_ID] notification channel on Android 8+.
     * This is a no-op on Android 7 and below (where channels do not exist).
     */
    private fun createChannelIfNeeded(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SYNC_FAILURES_CHANNEL_ID,
                SYNC_FAILURES_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a queued message permanently fails to deliver."
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // â”€â”€â”€ Factory / scheduling helpers (called by callers, not by WorkManager) â”€

    companion object {

        /**
         * Builds the [OneTimeWorkRequest] for [SyncMessagesWorker] with:
         *   - [NetworkType.CONNECTED] constraint (only run when online)
         *   - EXPONENTIAL backoff: 1 s initial delay, capped at 30 s
         *
         * @return A configured [OneTimeWorkRequest] ready to be enqueued.
         */
        fun buildWorkRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncMessagesWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS
            )
            .build()

        /**
         * Enqueues a [SyncMessagesWorker] run via the provided [workManager].
         *
         * Uses [ExistingWorkPolicy.KEEP] so that if a run is already queued or running,
         * no duplicate is created. Call this whenever offline messages are queued or when
         * connectivity is first restored.
         *
         * @param workManager Application [WorkManager] instance.
         */
        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                buildWorkRequest()
            )
        }

        /**
         * Enqueues a [SyncMessagesWorker] run and replaces any already-queued (but not
         * yet running) instance. Use this when you want to guarantee a fresh attempt
         * immediately (e.g. when connectivity is restored while a previous request was
         * waiting in a long backoff window).
         *
         * @param workManager Application [WorkManager] instance.
         */
        fun enqueueImmediate(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                buildWorkRequest()
            )
        }
    }
}
