/**
 * NotificationRouterTest.kt — app module unit tests
 *
 * Purpose: Verifies the notification routing logic in [NotificationRouter].
 *
 * Test matrix:
 *   1. RAG_INGESTION_COMPLETE with category enabled → [RoutingResult.Shown] on rag channel
 *   2. MESSAGE_DELIVERED with category disabled → [RoutingResult.Suppressed]
 *   3. SYSTEM_ALERT always shown regardless of preference toggles
 *   4. Unknown notification type → [RoutingResult.Ignored], no crash
 *   5. Missing `notification_type` field → [RoutingResult.Ignored]
 *   6. Missing `body` field → [RoutingResult.Ignored]
 *   7. RAG_INGESTION_COMPLETE with category disabled → [RoutingResult.Suppressed]
 *   8. MESSAGE_DELIVERED with category enabled → [RoutingResult.Shown] on delivery channel
 *
 * Testing approach:
 *   - [NotificationRouter] receives a [FakeNotificationPoster] so no real Android runtime
 *     notification machinery is needed.
 *   - [NotificationPreferencesSnapshot] is constructed inline — no DataStore involved.
 *   - Robolectric provides a test [Context] for the [NotificationRouter] constructor.
 *
 * Requirements: 16.1, 16.2, 16.4, 21.1
 */
package com.aiassistant.notification

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [NotificationRouter] notification routing logic.
 *
 * Uses Robolectric to provide a real Android [Context] on the JVM without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NotificationRouterTest {

    private lateinit var context: Context
    private lateinit var fakePoster: FakeNotificationPoster
    private lateinit var router: NotificationRouter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakePoster = FakeNotificationPoster()
        router = NotificationRouter(context, fakePoster)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Returns preferences with both categories enabled. */
    private fun allEnabled() = NotificationPreferencesSnapshot(
        ragIngestionEnabled = true,
        messageDeliveryEnabled = true
    )

    /** Returns preferences with both categories disabled. */
    private fun allDisabled() = NotificationPreferencesSnapshot(
        ragIngestionEnabled = false,
        messageDeliveryEnabled = false
    )

    /** Minimal valid FCM data map for a given type. */
    private fun dataFor(type: String, body: String = "Test body", title: String? = null) = buildMap {
        put(FCM_KEY_NOTIFICATION_TYPE, type)
        put(FCM_KEY_BODY, body)
        if (title != null) put(FCM_KEY_TITLE, title)
    }

    // ─── 1. RAG ingestion — category enabled ──────────────────────────────────

    /**
     * When the RAG ingestion category is enabled, the router must post a notification
     * on [CHANNEL_ID_RAG_INGESTION] and return [RoutingResult.Shown].
     *
     * Requirement 16.1: RAG ingestion-complete notifications are a supported category.
     * Requirement 16.4: notifications are shown when the category is enabled.
     */
    @Test
    fun `rag ingestion with category enabled - shows notification`() {
        val result = router.route(
            data = dataFor(NOTIF_TYPE_RAG_INGESTION),
            preferences = allEnabled()
        )

        assertTrue("Result should be Shown", result is RoutingResult.Shown)
        val shown = result as RoutingResult.Shown
        assertEquals(CHANNEL_ID_RAG_INGESTION, shown.channelId)
        assertEquals(NOTIF_ID_RAG, shown.notificationId)
        assertEquals(1, fakePoster.postedCount)
        assertEquals(NOTIF_ID_RAG, fakePoster.lastPostedId)
    }

    // ─── 2. Message delivery — category disabled ──────────────────────────────

    /**
     * When the message-delivery category is disabled, the router must suppress the
     * notification and return [RoutingResult.Suppressed].
     *
     * Requirement 16.4: notifications are suppressed when the category is toggled off.
     */
    @Test
    fun `message delivery with category disabled - suppresses notification`() {
        val prefs = NotificationPreferencesSnapshot(
            ragIngestionEnabled = true,
            messageDeliveryEnabled = false
        )
        val result = router.route(
            data = dataFor(NOTIF_TYPE_MESSAGE_DELIVERY),
            preferences = prefs
        )

        assertTrue("Result should be Suppressed", result is RoutingResult.Suppressed)
        assertEquals(0, fakePoster.postedCount)
    }

    // ─── 3. System alert — always shown regardless of toggles ─────────────────

    /**
     * System alerts must be shown even when all other categories are disabled.
     *
     * Requirement 16.1: system alert is a distinct notification category.
     * Requirement 16.4: system alerts bypass per-category preference toggles.
     */
    @Test
    fun `system alert with all categories disabled - still shows notification`() {
        val result = router.route(
            data = dataFor(NOTIF_TYPE_SYSTEM_ALERT),
            preferences = allDisabled()
        )

        assertTrue("System alerts must always be shown", result is RoutingResult.Shown)
        val shown = result as RoutingResult.Shown
        assertEquals(CHANNEL_ID_SYSTEM_ALERTS, shown.channelId)
        assertEquals(NOTIF_ID_SYSTEM, shown.notificationId)
        assertEquals(1, fakePoster.postedCount)
    }

    /**
     * System alerts must be shown even when `ragIngestionEnabled = false` and
     * `messageDeliveryEnabled = false`.
     */
    @Test
    fun `system alert with mixed disabled preferences - still shown`() {
        val prefs = NotificationPreferencesSnapshot(
            ragIngestionEnabled = false,
            messageDeliveryEnabled = false
        )
        val result = router.route(data = dataFor(NOTIF_TYPE_SYSTEM_ALERT), preferences = prefs)

        assertTrue(result is RoutingResult.Shown)
        assertEquals(CHANNEL_ID_SYSTEM_ALERTS, (result as RoutingResult.Shown).channelId)
    }

    // ─── 4. Unknown type → ignored, no crash ──────────────────────────────────

    /**
     * An unknown `notification_type` value must be ignored without throwing.
     *
     * Requirement 16.4 (implied): unrecognised types should not crash the service.
     */
    @Test
    fun `unknown notification type - returns Ignored and does not crash`() {
        val result = router.route(
            data = dataFor("completely_unknown_type"),
            preferences = allEnabled()
        )

        assertTrue("Unknown type must be Ignored", result is RoutingResult.Ignored)
        assertEquals(0, fakePoster.postedCount)
    }

    // ─── 5. Missing notification_type ─────────────────────────────────────────

    @Test
    fun `missing notification_type key - returns Ignored`() {
        val data = mapOf(FCM_KEY_BODY to "Body text")
        val result = router.route(data = data, preferences = allEnabled())

        assertTrue(result is RoutingResult.Ignored)
        assertEquals(0, fakePoster.postedCount)
    }

    // ─── 6. Missing body ──────────────────────────────────────────────────────

    @Test
    fun `missing body key - returns Ignored`() {
        val data = mapOf(FCM_KEY_NOTIFICATION_TYPE to NOTIF_TYPE_RAG_INGESTION)
        val result = router.route(data = data, preferences = allEnabled())

        assertTrue(result is RoutingResult.Ignored)
        assertEquals(0, fakePoster.postedCount)
    }

    // ─── 7. RAG ingestion — category disabled ─────────────────────────────────

    @Test
    fun `rag ingestion with category disabled - suppresses notification`() {
        val prefs = NotificationPreferencesSnapshot(
            ragIngestionEnabled = false,
            messageDeliveryEnabled = true
        )
        val result = router.route(
            data = dataFor(NOTIF_TYPE_RAG_INGESTION),
            preferences = prefs
        )

        assertTrue(result is RoutingResult.Suppressed)
        assertEquals(0, fakePoster.postedCount)
    }

    // ─── 8. Message delivery — category enabled ───────────────────────────────

    @Test
    fun `message delivery with category enabled - shows notification`() {
        val result = router.route(
            data = dataFor(NOTIF_TYPE_MESSAGE_DELIVERY),
            preferences = allEnabled()
        )

        assertTrue(result is RoutingResult.Shown)
        val shown = result as RoutingResult.Shown
        assertEquals(CHANNEL_ID_MESSAGE_DELIVERY, shown.channelId)
        assertEquals(NOTIF_ID_MESSAGE_DELIVERED, shown.notificationId)
        assertEquals(1, fakePoster.postedCount)
    }

    // ─── 9. Optional title falls back to default ──────────────────────────────

    /**
     * When the FCM payload omits the optional `title` field, a default title is used.
     * The notification must still be posted (not ignored).
     */
    @Test
    fun `rag ingestion without title key - uses default title and posts notification`() {
        val data = mapOf(
            FCM_KEY_NOTIFICATION_TYPE to NOTIF_TYPE_RAG_INGESTION,
            FCM_KEY_BODY to "Your document is ready."
            // no FCM_KEY_TITLE
        )
        val result = router.route(data = data, preferences = allEnabled())

        assertTrue(result is RoutingResult.Shown)
        assertEquals(1, fakePoster.postedCount)
    }

    // ─── 10. Empty payload ────────────────────────────────────────────────────

    @Test
    fun `empty data map - returns Ignored`() {
        val result = router.route(data = emptyMap(), preferences = allEnabled())

        assertTrue(result is RoutingResult.Ignored)
        assertEquals(0, fakePoster.postedCount)
    }
}

// ─── Test double: FakeNotificationPoster ─────────────────────────────────────

/**
 * Test double for [NotificationPoster] that records all [notify] calls.
 *
 * Avoids the need for a real [NotificationManagerCompat] or POST_NOTIFICATIONS permission
 * in unit tests.
 */
class FakeNotificationPoster : NotificationPoster {

    private val posted = mutableListOf<Pair<Int, Notification>>()

    /** Number of [notify] calls received so far. */
    val postedCount: Int get() = posted.size

    /** The notification ID supplied in the most recent [notify] call, or null if none. */
    val lastPostedId: Int? get() = posted.lastOrNull()?.first

    /** The [Notification] supplied in the most recent [notify] call, or null if none. */
    val lastPostedNotification: Notification? get() = posted.lastOrNull()?.second

    /** All (id, notification) pairs recorded so far. */
    val allPosted: List<Pair<Int, Notification>> get() = posted.toList()

    override fun notify(notificationId: Int, notification: Notification) {
        posted.add(notificationId to notification)
    }

    /** Clears all recorded calls. Useful for resetting between tests. */
    fun reset() = posted.clear()
}
