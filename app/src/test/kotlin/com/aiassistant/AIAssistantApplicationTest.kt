package com.aiassistant

import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aiassistant.BuildConfig
import com.aiassistant.analytics.RemoteConfigManager
import com.aiassistant.core.common.observability.ObservabilityEventBus
import com.aiassistant.core.common.observability.SessionManager
import com.aiassistant.core.network.observability.scheduleObservabilityUpload
import com.aiassistant.notification.NotificationChannelManager
import com.aiassistant.observability.AppLifecycleObserver
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AIAssistantApplicationTest {

    private lateinit var app: AIAssistantApplication
    private val workerFactory = mockk<HiltWorkerFactory>(relaxed = true)
    private val observabilityEventBus = mockk<ObservabilityEventBus>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val notificationChannelManager = mockk<NotificationChannelManager>(relaxed = true)
    private val remoteConfigManager = mockk<RemoteConfigManager>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(FirebaseApp::class)
        mockkStatic(FirebaseCrashlytics::class)
        mockkStatic(Timber::class)
        mockkStatic(ProcessLifecycleOwner::class)
        mockkStatic("com.aiassistant.core.network.observability.ObservabilityUploadWorkerKt")

        every { FirebaseApp.initializeApp(any()) } returns mockk()
        every { FirebaseCrashlytics.getInstance() } returns mockk(relaxed = true)

        val lifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
        val lifecycle = mockk<Lifecycle>(relaxed = true)
        every { ProcessLifecycleOwner.get() } returns lifecycleOwner
        every { lifecycleOwner.lifecycle } returns lifecycle

        app = AIAssistantApplication().apply {
            this.workerFactory = this@AIAssistantApplicationTest.workerFactory
            this.observabilityEventBus = this@AIAssistantApplicationTest.observabilityEventBus
            this.sessionManager = this@AIAssistantApplicationTest.sessionManager
            this.notificationChannelManager = this@AIAssistantApplicationTest.notificationChannelManager
            this.remoteConfigManager = this@AIAssistantApplicationTest.remoteConfigManager
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `onCreate should initialize all components`() {
        // Given
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        every { FirebaseCrashlytics.getInstance() } returns crashlytics
        coEvery { remoteConfigManager.fetchAndActivate() } returns true
        every { scheduleObservabilityUpload(any()) } returns Unit

        // When
        app.onCreate()

        // Then
        verify { FirebaseApp.initializeApp(app) }
        verify { crashlytics.setCrashlyticsCollectionEnabled(any()) }
        verify { notificationChannelManager.ensureChannelsCreated() }
        verify { scheduleObservabilityUpload(app) }
        verify { ProcessLifecycleOwner.get().lifecycle.addObserver(any<AppLifecycleObserver>()) }

        // RemoteConfig should be launched in background
        coVerify(timeout = 1000) { remoteConfigManager.fetchAndActivate() }
    }

    @Test
    fun `workManagerConfiguration should be configured`() {
        // When
        val config = app.workManagerConfiguration

        // Then
        assert(config != null)
    }

    @Test
    fun `onCreate should plant Timber DebugTree in debug build`() {
        // Since BuildConfig.DEBUG is a compile-time constant,
        // we might need to be careful. In unit tests it might depend on the variant.
        // AIAssistantApplication checks BuildConfig.DEBUG.

        // If we want to test both branches, we might need to use reflection or a wrapper.
        // But let's verify it calls Timber.plant if BuildConfig.DEBUG is true.
        if (BuildConfig.DEBUG) {
            app.onCreate()
            verify { Timber.plant(any<Timber.DebugTree>()) }
        }
    }
}
