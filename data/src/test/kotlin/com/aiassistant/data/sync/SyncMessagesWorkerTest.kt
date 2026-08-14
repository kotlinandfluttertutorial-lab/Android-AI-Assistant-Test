/**
 * SyncMessagesWorkerTest.kt — data module
 *
 * Purpose: Unit tests for [SyncMessagesWorker] covering:
 *   - Connectivity-gate behaviour (pre-flight and mid-loop)
 *   - Successful message sync with server-wins conflict resolution
 *   - Failure counting: 1–2 errors leave message "pending"; 3rd marks it "failed"
 *   - Notification posted on permanent failure
 *   - [SyncMessagesWorker.buildWorkRequest] backoff configuration
 *
 * Architecture: data module — unit tests using MockK for all Android dependencies.
 *               Worker is instantiated directly (bypassing Hilt). NotificationCompat.Builder
 *               is mocked via mockkConstructor so no real Android context/Robolectric needed.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure and assertions
 * - MockK                — mocking local/remote data sources, connectivity, context, NM
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 21.1 (retry logic, backoff, failure notification)
 */
package com.aiassistant.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.database.entity.MessageEntity
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.local.MessageLocalDataSource
import com.aiassistant.data.remote.message.MessageDto
import com.aiassistant.data.remote.message.MessageRemoteDataSource
import com.aiassistant.data.repository.TestDispatcherProvider
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

// ─── Test helpers ─────────────────────────────────────────────────────────────

private fun fakeEntity(
    id: String = "msg-1",
    conversationId: String = "conv-1",
    content: String = "Hello",
    provider: String = "openai",
    syncStatus: String = "pending"
) = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = "user",
    content = content,
    inputTokens = 0,
    outputTokens = 0,
    provider = provider,
    syncStatus = syncStatus,
    createdAt = 1_000_000L
)

private fun fakeDto(
    id: String = "msg-1",
    content: String = "Server content",
    inputTokens: Int = 10,
    outputTokens: Int = 20,
    provider: String = "openai"
) = MessageDto(
    id = id,
    conversationId = "conv-1",
    role = "assistant",
    content = content,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    provider = provider,
    createdAt = 2_000_000L
)

private fun fakeDomainError() = DomainError.ServerError(
    message = "Server error",
    httpStatusCode = 500
)

// ─── Spec ─────────────────────────────────────────────────────────────────────

class SyncMessagesWorkerTest :
    DescribeSpec({

        // ── Shared mocks ──────────────────────────────────────────────────────────
        val localSource: MessageLocalDataSource = mockk(relaxed = true)
        val remoteSource: MessageRemoteDataSource = mockk()
        val connectivity: ConnectivityObserver = mockk()
        val dispatchers = TestDispatcherProvider()

        // Mock Android Context + NotificationManager so Robolectric is not needed
        val notificationManager: NotificationManager = mockk(relaxed = true)
        val appContext: Context = mockk(relaxed = true)

        /** Builds a [SyncMessagesWorker] by directly invoking the constructor, bypassing Hilt. */
        fun buildWorker(): SyncMessagesWorker {
            val workerParams: WorkerParameters = mockk(relaxed = true)
            return SyncMessagesWorker(
                appContext = appContext,
                workerParams = workerParams,
                localSource = localSource,
                remoteSource = remoteSource,
                connectivity = connectivity,
                dispatchers = dispatchers
            )
        }

        beforeEach {
            clearAllMocks()
            // Default stubs to avoid "no answer" on commonly-called methods
            every { connectivity.isConnectedFlow } returns flowOf(true)
            // Stub getSystemService so postFailureNotification can obtain the NM mock
            every { appContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
            // Stub createNotificationChannel (Android 8+) as no-op
            every { notificationManager.createNotificationChannel(any<NotificationChannel>()) } returns Unit
        }

        // ── doWork() — pre-flight connectivity gate ───────────────────────────────
        describe("doWork() — no connectivity on entry") {
            it("returns Result.retry() and never calls remoteSource.sendMessage") {
                runTest {
                    every { connectivity.isConnected() } returns false

                    val result = buildWorker().doWork()

                    result shouldBe Result.retry()
                    coVerify(exactly = 0) { remoteSource.sendMessage(any(), any(), any()) }
                }
            }
        }

        // ── doWork() — empty pending queue ───────────────────────────────────────
        describe("doWork() — no pending messages") {
            it("returns Result.success() immediately") {
                runTest {
                    every { connectivity.isConnected() } returns true
                    coEvery { localSource.getPendingMessages() } returns emptyList()

                    val result = buildWorker().doWork()

                    result shouldBe Result.success()
                    coVerify(exactly = 0) { remoteSource.sendMessage(any(), any(), any()) }
                }
            }
        }

        // ── doWork() — single message succeeds ───────────────────────────────────
        describe("doWork() — single message succeeds") {
            it("calls updateMessage with syncStatus='synced' and server-wins values") {
                runTest {
                    val entity = fakeEntity()
                    val dto = fakeDto(
                        content = "Server wins content",
                        inputTokens = 15,
                        outputTokens = 30,
                        provider = "anthropic"
                    )
                    every { connectivity.isConnected() } returns true
                    coEvery { localSource.getPendingMessages() } returns listOf(entity)
                    coEvery { remoteSource.sendMessage("conv-1", "Hello", "openai") } returns ApiResult.Success(dto)

                    val result = buildWorker().doWork()

                    result shouldBe Result.success()
                    coVerify(exactly = 1) {
                        localSource.updateMessage(
                            match { updated ->
                                updated.id == "msg-1" &&
                                    updated.syncStatus == "synced" &&
                                    updated.content == "Server wins content" &&
                                    updated.inputTokens == 15 &&
                                    updated.outputTokens == 30 &&
                                    updated.provider == "anthropic"
                            }
                        )
                    }
                    coVerify(exactly = 0) { localSource.updateSyncStatus(any(), any()) }
                }
            }
        }

        // ── doWork() — ApiResult.NetworkUnavailable mid-loop ─────────────────────
        describe("doWork() — ApiResult.NetworkUnavailable from sendMessage") {
            it("returns Result.retry() and does NOT mark message as failed") {
                runTest {
                    val entity = fakeEntity()
                    every { connectivity.isConnected() } returns true
                    coEvery { localSource.getPendingMessages() } returns listOf(entity)
                    coEvery { remoteSource.sendMessage(any(), any(), any()) } returns ApiResult.NetworkUnavailable

                    val result = buildWorker().doWork()

                    result shouldBe Result.retry()
                    coVerify(exactly = 0) { localSource.updateSyncStatus(any(), any()) }
                    coVerify(exactly = 0) { localSource.updateMessage(any()) }
                }
            }
        }

        // ── doWork() — connectivity.isConnected() → false mid-loop ───────────────
        describe("doWork() — connectivity lost after pre-flight check") {
            it("returns Result.retry() without processing messages") {
                runTest {
                    val entity = fakeEntity()
                    coEvery { localSource.getPendingMessages() } returns listOf(entity)
                    // First call (pre-flight) passes; second call (inside loop) fails
                    var callCount = 0
                    every { connectivity.isConnected() } answers {
                        callCount++
                        callCount <= 1 // true on first, false on second
                    }

                    val result = buildWorker().doWork()

                    result shouldBe Result.retry()
                    coVerify(exactly = 0) { remoteSource.sendMessage(any(), any(), any()) }
                }
            }
        }

        // ── doWork() — single error, message stays pending ────────────────────────
        describe("doWork() — single ApiResult.Error (1st failure)") {
            it("does NOT call updateSyncStatus and returns Result.success()") {
                runTest {
                    val entity = fakeEntity()
                    every { connectivity.isConnected() } returns true
                    coEvery { localSource.getPendingMessages() } returns listOf(entity)
                    coEvery { remoteSource.sendMessage(any(), any(), any()) } returns ApiResult.Error(fakeDomainError())

                    val result = buildWorker().doWork()

                    // Worker completes without retry — message stays pending for next worker run
                    result shouldBe Result.success()
                    coVerify(exactly = 0) { localSource.updateSyncStatus(any(), any()) }
                    coVerify(exactly = 0) { localSource.updateMessage(any()) }
                }
            }
        }

        // ── doWork() — 2nd error still does NOT mark failed ──────────────────────
        describe("doWork() — two ApiResult.Error for the same message id (2nd failure)") {
            it("does NOT call updateSyncStatus after 2 failures and returns Result.success()") {
                runTest {
                    // Same entity id appearing twice in one run = 2 failure attempts
                    val entity = fakeEntity(id = "msg-dup")
                    every { connectivity.isConnected() } returns true
                    coEvery { localSource.getPendingMessages() } returns listOf(entity, entity)
                    coEvery { remoteSource.sendMessage(any(), any(), any()) } returns ApiResult.Error(fakeDomainError())

                    val result = buildWorker().doWork()

                    result shouldBe Result.success()
                    // 2 attempts < MAX_DELIVERY_ATTEMPTS (3) — not yet permanently failed
                    coVerify(exactly = 0) { localSource.updateSyncStatus(any(), any()) }
                }
            }
        }

        // ── doWork() — 3rd error marks message "failed" ───────────────────────────
        describe("doWork() — three ApiResult.Error for the same message id (3rd failure)") {
            it("calls updateSyncStatus(id, 'failed') exactly once after 3rd failure") {
                runTest {
                    // Mock NotificationCompat.Builder to avoid NPE without real Android context
                    mockkConstructor(NotificationCompat.Builder::class)
                    val fakeNotification: Notification = mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().build() } returns fakeNotification
                    every { anyConstructed<NotificationCompat.Builder>().setSmallIcon(any<Int>()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setContentTitle(any()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setContentText(any()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setStyle(any()) } returns mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setPriority(any()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setAutoCancel(any()) } returns
                        mockk(relaxed = true)

                    val entity = fakeEntity(id = "msg-tri")
                    every { connectivity.isConnected() } returns true
                    // Same entity 3 times → failure count reaches MAX_DELIVERY_ATTEMPTS (3)
                    coEvery { localSource.getPendingMessages() } returns listOf(entity, entity, entity)
                    coEvery { remoteSource.sendMessage(any(), any(), any()) } returns ApiResult.Error(fakeDomainError())

                    val result = buildWorker().doWork()

                    result shouldBe Result.success()
                    // updateSyncStatus called exactly once — only on the 3rd failure
                    coVerify(exactly = 1) { localSource.updateSyncStatus("msg-tri", "failed") }
                    coVerify(exactly = 0) { localSource.updateMessage(any()) }

                    unmockkConstructor(NotificationCompat.Builder::class)
                }
            }
        }

        // ── doWork() — notification posted on permanent failure ───────────────────
        describe("doWork() — notification is posted after 3rd failure") {
            it("calls notificationManager.notify() once with entity.id.hashCode() as notification ID") {
                runTest {
                    // Mock NotificationCompat.Builder to avoid NPE without real Android context
                    mockkConstructor(NotificationCompat.Builder::class)
                    val fakeNotification: Notification = mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().build() } returns fakeNotification
                    every { anyConstructed<NotificationCompat.Builder>().setSmallIcon(any<Int>()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setContentTitle(any()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setContentText(any()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setStyle(any()) } returns mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setPriority(any()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setAutoCancel(any()) } returns
                        mockk(relaxed = true)

                    val entity = fakeEntity(id = "msg-notify", content = "Notify me")
                    every { connectivity.isConnected() } returns true
                    coEvery { localSource.getPendingMessages() } returns listOf(entity, entity, entity)
                    coEvery { remoteSource.sendMessage(any(), any(), any()) } returns ApiResult.Error(fakeDomainError())

                    buildWorker().doWork()

                    // Verify exactly one notification was posted with the expected ID
                    verify(exactly = 1) { notificationManager.notify("msg-notify".hashCode(), any()) }

                    unmockkConstructor(NotificationCompat.Builder::class)
                }
            }
        }

        // ── doWork() — permanently failed message is skipped in next pass ─────────
        describe("doWork() — message exceeded MAX_DELIVERY_ATTEMPTS is skipped") {
            it("sendMessage called exactly 3 times and 4th occurrence is skipped") {
                runTest {
                    // Mock NotificationCompat.Builder to avoid NPE without real Android context
                    mockkConstructor(NotificationCompat.Builder::class)
                    val fakeNotification: Notification = mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().build() } returns fakeNotification
                    every { anyConstructed<NotificationCompat.Builder>().setSmallIcon(any<Int>()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setContentTitle(any()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setContentText(any()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setStyle(any()) } returns mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setPriority(any()) } returns
                        mockk(relaxed = true)
                    every { anyConstructed<NotificationCompat.Builder>().setAutoCancel(any()) } returns
                        mockk(relaxed = true)

                    // 3 errors → permanent fail; 4th occurrence should be skipped
                    val entity = fakeEntity(id = "msg-over-limit")
                    every { connectivity.isConnected() } returns true
                    coEvery { localSource.getPendingMessages() } returns listOf(entity, entity, entity, entity)
                    coEvery { remoteSource.sendMessage(any(), any(), any()) } returns ApiResult.Error(fakeDomainError())

                    val result = buildWorker().doWork()

                    result shouldBe Result.success()
                    // sendMessage called exactly 3 times — 4th is skipped
                    coVerify(exactly = 3) { remoteSource.sendMessage(any(), any(), any()) }
                    // markAsFailed called exactly once (on 3rd failure)
                    coVerify(exactly = 1) { localSource.updateSyncStatus("msg-over-limit", "failed") }

                    unmockkConstructor(NotificationCompat.Builder::class)
                }
            }
        }

        // ── buildWorkRequest() — backoff policy ───────────────────────────────────
        describe("buildWorkRequest()") {
            it("uses EXPONENTIAL backoff policy") {
                val request = SyncMessagesWorker.buildWorkRequest()
                request.workSpec.backoffPolicy shouldBe BackoffPolicy.EXPONENTIAL
            }

            it("backoff delay is set (WorkManager clamps to minimum 10 s)") {
                val request = SyncMessagesWorker.buildWorkRequest()
                // WorkManager enforces WorkRequest.MIN_BACKOFF_MILLIS (10_000 ms minimum).
                // The worker configures BACKOFF_DELAY_SECONDS = 1 s; WorkManager clamps it.
                val storedDelay = request.workSpec.backoffDelayDuration
                storedDelay shouldBe WorkRequest.MIN_BACKOFF_MILLIS
            }

            it("requires NetworkType.CONNECTED constraint") {
                val request = SyncMessagesWorker.buildWorkRequest()
                val constraints = request.workSpec.constraints
                constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
            }
        }
    })
