/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : ConnectivityObserver.kt
 * Purpose    : ConnectivityObserver — core-network module component
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : Kotlin Class
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
 * Module     : core-network
 * File       : ConnectivityObserver.kt
 * Purpose    : ConnectivityObserver — core-network module component
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : Kotlin Class
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
 * ConnectivityObserver.kt â€” core-network module
 *
 * Purpose: Abstraction + implementation for observing real-time network availability as a
 *          Kotlin [Flow]. The rest of the application depends only on the interface so that
 *          the Android [android.net.ConnectivityManager] implementation can be replaced in
 *          unit tests without Robolectric.
 *
 * Architecture: core-network â€” consumed by the `data` module repositories (e.g.
 *               [ConversationRepositoryImpl]) and by the [offline banner] in `core-ui`.
 * Dependencies: Android ConnectivityManager, kotlinx.coroutines
 *
 * Design decisions:
 * - The interface emits `Flow<Boolean>` (true = connected) rather than a richer enum so
 *   callers that only need a simple online/offline check stay readable.
 * - [NetworkConnectivityObserver] uses `callbackFlow` with conflation so collectors
 *   always see the most recent value; missed updates during a long back-pressure window
 *   do not queue up.
 * - An immediate synchronous check at subscription time ensures the flow starts with the
 *   correct current state rather than waiting for the next network change.
 * - Only WIFI and CELLULAR capabilities are treated as "connected" â€” Bluetooth PAN,
 *   Ethernet, and VPN transports are intentionally excluded to reflect the user-facing
 *   definition of "mobile connectivity".
 * - The callback is unregistered in `awaitClose` to prevent memory leaks when the scope
 *   collecting the flow is cancelled.
 * - Annotated `@Singleton` and injected by [NetworkModule] so the OS callback is
 *   registered only once for the lifetime of the process.
 *
 * Requirements: 10.4 â€” display persistent offline banner when no network connectivity.
 */
package com.aiassistant.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Observes network availability and exposes it as a [Flow].
 */
interface ConnectivityObserver {

    /**
     * Hot flow that emits the current connectivity state immediately on collection and
     * on every subsequent change.
     *
     * - `true`  â†’ WIFI or CELLULAR is available (device can reach the internet).
     * - `false` â†’ no usable network interface is available.
     */
    val isConnectedFlow: Flow<Boolean>

    /**
     * Synchronous snapshot of the current connectivity state.
     * Useful in non-coroutine contexts (e.g. WorkManager workers).
     *
     * @return `true` if WIFI or CELLULAR is currently available.
     */
    fun isConnected(): Boolean
}

/**
 * Production implementation backed by [ConnectivityManager].
 *
 * Registers a [ConnectivityManager.NetworkCallback] for WIFI and CELLULAR transports.
 * The flow is conflated so that rapid state changes (e.g. switching between WIFI and
 * CELLULAR) do not overwhelm downstream collectors.
 */
@Singleton
class NetworkConnectivityObserver @Inject constructor(@ApplicationContext private val context: Context) :
    ConnectivityObserver {

    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override val isConnectedFlow: Flow<Boolean> = callbackFlow {
        // Emit the current state immediately so collectors start with the right value.
        trySend(isConnected())

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                // Check whether any other network is still available before emitting false.
                trySend(isConnected())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                val isValidated = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                trySend(hasInternet && isValidated)
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.conflate()

    override fun isConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
