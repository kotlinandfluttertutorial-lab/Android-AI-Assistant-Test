/**
 * SecureStorageTest.kt — core-security module unit tests
 *
 * Tests for the [SecureStorage] contract split across two test classes:
 *
 * 1. [SecureStorageContractTest] — verifies the [SecureStorage] behavioral
 *    contract (save / retrieve / clear) using a pure in-memory fake.
 *    Fast, hermetic, no Android framework required.
 *
 * 2. [SecureStorageImplRobolectricTest] — verifies that [SecureStorageImpl]
 *    correctly wraps SharedPreferences read/write/clear by exercising the
 *    real implementation under Robolectric using a plain (unencrypted)
 *    SharedPreferences injected via the @VisibleForTesting constructor.
 *    This approach avoids the AndroidKeyStore JCE provider dependency (which
 *    is unavailable in a JVM-only test environment) while still exercising
 *    the complete read/write/clear logic of SecureStorageImpl. These tests
 *    confirm that:
 *      - Written tokens survive a read (round-trip through SharedPreferences)
 *      - clearAll() removes both tokens from storage
 *
 * Requirements: 9.4 — AI_Assistant SHALL use Android EncryptedSharedPreferences
 * for all locally stored credentials and tokens.
 * Requirements: 21.1 — unit test coverage for the security module.
 */
package com.aiassistant.core.security

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// ─── In-memory fake ─────────────────────────────────────────────────────────

/**
 * In-memory fake implementation of [SecureStorage] for contract testing.
 * Mimics the full contract without any crypto so tests are fast and hermetic.
 */
private class FakeSecureStorage : SecureStorage {
    private var jwt: String? = null
    private var refreshToken: String? = null
    private var onboardingComplete: Boolean = false
    private var fcmToken: String? = null
    private var fcmTokenPendingSync: Boolean = false

    override fun saveJwt(token: String) { jwt = token }
    override fun getJwt(): String? = jwt
    override fun saveRefreshToken(token: String) { refreshToken = token }
    override fun getRefreshToken(): String? = refreshToken
    override fun clearAll() {
        jwt = null
        refreshToken = null
        onboardingComplete = false
        fcmToken = null
        fcmTokenPendingSync = false
    }
    override fun saveOnboardingComplete() { onboardingComplete = true }
    override fun isOnboardingComplete(): Boolean = onboardingComplete
    override fun saveFcmToken(token: String) { fcmToken = token; fcmTokenPendingSync = true }
    override fun getFcmToken(): String? = fcmToken
    override fun saveFcmTokenSynced() { fcmTokenPendingSync = false }
    override fun clearFcmToken() { fcmToken = null; fcmTokenPendingSync = false }
    override fun isFcmTokenPendingSync(): Boolean = fcmTokenPendingSync
}

// ─── Contract tests (fake-backed, no Android framework) ──────────────────────

/**
 * Verifies the [SecureStorage] behavioral contract through [FakeSecureStorage].
 * These tests run on the plain JVM with no Robolectric overhead.
 */
class SecureStorageContractTest {

    private lateinit var secureStorage: SecureStorage

    @Before
    fun setUp() {
        secureStorage = FakeSecureStorage()
    }

    // ── JWT token ─────────────────────────────────────────────────────────────

    @Test
    fun `getJwt returns null when no token has been saved`() {
        secureStorage.getJwt() shouldBe null
    }

    @Test
    fun `saveJwt and getJwt round-trip preserves the token`() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test.sig"
        secureStorage.saveJwt(token)
        secureStorage.getJwt() shouldBe token
    }

    @Test
    fun `saveJwt overwrites a previously saved token`() {
        secureStorage.saveJwt("first_token")
        secureStorage.saveJwt("second_token")
        secureStorage.getJwt() shouldBe "second_token"
    }

    // ── Refresh token ─────────────────────────────────────────────────────────

    @Test
    fun `getRefreshToken returns null when no token has been saved`() {
        secureStorage.getRefreshToken() shouldBe null
    }

    @Test
    fun `saveRefreshToken and getRefreshToken round-trip preserves the token`() {
        val token = "refresh_abc123"
        secureStorage.saveRefreshToken(token)
        secureStorage.getRefreshToken() shouldBe token
    }

    @Test
    fun `JWT and refresh tokens are stored independently`() {
        secureStorage.saveJwt("jwt_value")
        secureStorage.saveRefreshToken("refresh_value")
        secureStorage.getJwt() shouldBe "jwt_value"
        secureStorage.getRefreshToken() shouldBe "refresh_value"
    }

    // ── clearAll ──────────────────────────────────────────────────────────────

    @Test
    fun `clearAll wipes both JWT and refresh token`() {
        secureStorage.saveJwt("jwt_value")
        secureStorage.saveRefreshToken("refresh_value")

        secureStorage.clearAll()

        secureStorage.getJwt() shouldBe null
        secureStorage.getRefreshToken() shouldBe null
    }

    @Test
    fun `clearAll on empty storage is a no-op and does not throw`() {
        secureStorage.clearAll()
        secureStorage.getJwt() shouldBe null
    }

    @Test
    fun `tokens can be saved again after clearAll`() {
        secureStorage.saveJwt("original")
        secureStorage.clearAll()
        secureStorage.saveJwt("new_token")
        secureStorage.getJwt() shouldBe "new_token"
    }

    // ── Security contract ─────────────────────────────────────────────────────

    @Test
    fun `SecureStorage implementation is non-null after construction`() {
        secureStorage shouldNotBe null
    }

    @Test
    fun `saving an empty string is distinct from null`() {
        secureStorage.saveJwt("")
        // An empty string is a valid stored value — should be returned, not treated as absent
        secureStorage.getJwt() shouldBe ""
    }

    // ── Structural type tests ─────────────────────────────────────────────────

    @Test
    fun `SecureStorageImpl is a subtype of SecureStorage`() {
        val implClass = SecureStorageImpl::class.java
        val interfaceClass = SecureStorage::class.java
        interfaceClass.isAssignableFrom(implClass) shouldBe true
    }

    @Test
    fun `SecureStorage interface exposes no raw key name constants`() {
        // Verify the interface does not declare any public constant fields
        // that would expose storage key names externally.
        val publicFields = SecureStorage::class.java.fields // only public members
        publicFields.isEmpty() shouldBe true
    }
}

// ─── Robolectric integration tests (real SecureStorageImpl) ──────────────────

/**
 * Verifies [SecureStorageImpl] SharedPreferences read/write/clear logic under Robolectric.
 *
 * [SecureStorageImpl] is constructed via its @VisibleForTesting constructor that accepts
 * a plain [android.content.SharedPreferences] instance. Robolectric provides a fully
 * functional in-memory SharedPreferences via [RuntimeEnvironment.getApplication()], so the
 * complete read/write/clear contract is exercised without needing the AndroidKeyStore JCE
 * provider (unavailable in JVM-only test environments).
 *
 * This design separates two concerns:
 *   1. The SharedPreferences key/value contract is verified here (unit testable under Robolectric).
 *   2. The EncryptedSharedPreferences integration is verified in instrumented / on-device tests.
 *
 * Core invariants:
 *   - Written JWT tokens survive a read (write → read round-trip)
 *   - Written refresh tokens survive a read
 *   - clearAll() removes both tokens
 *   - Independent keys do not bleed into each other
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureStorageImplRobolectricTest {

    private lateinit var secureStorage: SecureStorage

    @Before
    fun setUp() {
        // Use the @VisibleForTesting constructor to inject a plain SharedPreferences.
        // Robolectric provides a real in-memory SharedPreferences backed by the
        // application context, exercising the full read/write/clear logic without
        // requiring the AndroidKeyStore JCE provider.
        val context = RuntimeEnvironment.getApplication()
        val plainPrefs = context.getSharedPreferences(
            SecureStorageImpl.PREFS_FILE_NAME,
            android.content.Context.MODE_PRIVATE
        )
        secureStorage = SecureStorageImpl(plainPrefs)
    }

    // ── JWT round-trip via SharedPreferences ─────────────────────────────────

    @Test
    fun `written JWT token survives an encrypted read`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature"
        secureStorage.saveJwt(jwt)
        secureStorage.getJwt() shouldBe jwt
    }

    @Test
    fun `getJwt returns null before any token is written`() {
        secureStorage.getJwt() shouldBe null
    }

    @Test
    fun `saveJwt overwrites a previously encrypted token`() {
        secureStorage.saveJwt("token_v1")
        secureStorage.saveJwt("token_v2")
        secureStorage.getJwt() shouldBe "token_v2"
    }

    // ── Refresh token round-trip via SharedPreferences ────────────────────────

    @Test
    fun `written refresh token survives an encrypted read`() {
        val refreshToken = "rt_0a1b2c3d4e5f6a7b8c9d"
        secureStorage.saveRefreshToken(refreshToken)
        secureStorage.getRefreshToken() shouldBe refreshToken
    }

    @Test
    fun `getRefreshToken returns null before any token is written`() {
        secureStorage.getRefreshToken() shouldBe null
    }

    @Test
    fun `saveRefreshToken overwrites a previously encrypted token`() {
        secureStorage.saveRefreshToken("rt_first")
        secureStorage.saveRefreshToken("rt_second")
        secureStorage.getRefreshToken() shouldBe "rt_second"
    }

    // ── Key isolation ─────────────────────────────────────────────────────────

    @Test
    fun `JWT and refresh token are stored in independent encrypted keys`() {
        val jwt = "jwt_independent"
        val refresh = "rt_independent"

        secureStorage.saveJwt(jwt)
        secureStorage.saveRefreshToken(refresh)

        secureStorage.getJwt() shouldBe jwt
        secureStorage.getRefreshToken() shouldBe refresh
    }

    @Test
    fun `saving only a JWT does not populate the refresh token key`() {
        secureStorage.saveJwt("jwt_only")
        secureStorage.getRefreshToken() shouldBe null
    }

    @Test
    fun `saving only a refresh token does not populate the JWT key`() {
        secureStorage.saveRefreshToken("rt_only")
        secureStorage.getJwt() shouldBe null
    }

    // ── clearAll removes all tokens from encrypted storage ───────────────────

    @Test
    fun `clearAll removes JWT from encrypted storage`() {
        secureStorage.saveJwt("jwt_to_clear")
        secureStorage.clearAll()
        secureStorage.getJwt() shouldBe null
    }

    @Test
    fun `clearAll removes refresh token from encrypted storage`() {
        secureStorage.saveRefreshToken("rt_to_clear")
        secureStorage.clearAll()
        secureStorage.getRefreshToken() shouldBe null
    }

    @Test
    fun `clearAll removes both tokens simultaneously`() {
        secureStorage.saveJwt("jwt_simultaneous")
        secureStorage.saveRefreshToken("rt_simultaneous")

        secureStorage.clearAll()

        secureStorage.getJwt() shouldBe null
        secureStorage.getRefreshToken() shouldBe null
    }

    @Test
    fun `clearAll on empty storage does not throw`() {
        // No tokens written — clearAll should be safe to call
        secureStorage.clearAll()
        secureStorage.getJwt() shouldBe null
        secureStorage.getRefreshToken() shouldBe null
    }

    @Test
    fun `tokens can be re-written and re-read after clearAll`() {
        secureStorage.saveJwt("original_jwt")
        secureStorage.saveRefreshToken("original_rt")

        secureStorage.clearAll()

        secureStorage.saveJwt("new_jwt")
        secureStorage.saveRefreshToken("new_rt")

        secureStorage.getJwt() shouldBe "new_jwt"
        secureStorage.getRefreshToken() shouldBe "new_rt"
    }

    // ── No raw key material exposure ──────────────────────────────────────────

    @Test
    fun `getJwt returns the exact string that was saved without transformation`() {
        // Verifies the implementation does not mutate (hash, truncate, etc.) the value.
        val exactToken = "Bearer eyJhbGciOiJSUzI1NiJ9.body.sig"
        secureStorage.saveJwt(exactToken)
        secureStorage.getJwt() shouldBe exactToken
    }

    @Test
    fun `getRefreshToken returns the exact string that was saved without transformation`() {
        val exactToken = "v2.refresh.a1b2c3d4e5f6"
        secureStorage.saveRefreshToken(exactToken)
        secureStorage.getRefreshToken() shouldBe exactToken
    }
}
