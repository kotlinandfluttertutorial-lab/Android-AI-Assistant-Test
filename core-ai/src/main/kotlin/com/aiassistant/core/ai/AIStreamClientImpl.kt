/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : AIStreamClientImpl.kt
 * Purpose    : AIStreamClientImpl — core-ai module component
 *
 * Architecture Layer : Core-AI
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
 * Module     : core-ai
 * File       : AIStreamClientImpl.kt
 * Purpose    : AIStreamClientImpl — core-ai module component
 *
 * Architecture Layer : Core-AI
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
 * AIStreamClientImpl.kt
 *
 * Purpose: OkHttp WebSocket-based implementation of [AIStreamClient].
 *          Connects to `wss://<baseUrl>/ws/chat/{conversationId}?token={jwt}`,
 *          parses structured JSON events into [StreamEvent] subtypes, and
 *          reconnects automatically with exponential backoff on failure.
 *
 * Architecture: core-ai â€” no Android framework dependencies beyond OkHttp/Coroutines.
 * Dependencies: OkHttp, kotlinx.serialization, Hilt, core-common (DispatcherProvider)
 *
 * Requirements: 26.4 (exponential backoff reconnection), 26.5 (event parsing), 2.8
 *
 * Design decisions:
 * - `connect()` returns a cold `callbackFlow` so the WebSocket lifecycle is tied to
 *   Flow collection; cancellation automatically closes the socket.
 * - `@Volatile webSocket` reference is safe for the single-producer (OkHttp callback
 *   thread) / multi-consumer (any thread calling sendMessage/disconnect) pattern here.
 * - Reconnect logic lives inside the Flow producer so it is automatically cancelled
 *   when the collector is cancelled.
 * - `Done` events close the flow normally â€” no reconnect is attempted.
 * - JSON parsing errors produce `StreamEvent.Error` rather than propagating exceptions,
 *   keeping the Flow alive for subsequent frames.
 */

package com.aiassistant.core.ai

import com.aiassistant.core.common.DispatcherProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Exponential backoff reconnection configuration (Requirement 26.4). */
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val INITIAL_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 30_000L

/**
 * WebSocket base URL used by core-ai.
 *
 * Matches the backend host defined in `core-network`'s [NetworkModule] but uses the `wss`
 * scheme required for WebSocket connections. Adjust per environment via a Hilt qualifier or
 * BuildConfig field as the project evolves.
 */
private const val WS_BASE_URL = "ws://192.168.0.158:8000"

@Singleton
class AIStreamClientImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider
) : AIStreamClient {

    /** Active WebSocket connection, or `null` when disconnected. */
    @Volatile
    private var webSocket: WebSocket? = null

    /**
     * JSON codec shared across parse calls.
     * `ignoreUnknownKeys = true` keeps parsing lenient for forward compatibility.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // â”€â”€â”€ AIStreamClient API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a cold [Flow] that opens a WebSocket connection when collected and
     * emits [StreamEvent] values until the stream completes or all reconnect attempts
     * are exhausted.
     *
     * Cancelling the downstream collector closes the WebSocket and stops any pending
     * reconnect via `awaitClose { }`.
     */
    override fun connect(conversationId: String, jwt: String): Flow<StreamEvent> = callbackFlow {
        var attempt = 0

        /**
         * Opens a single WebSocket connection and attaches a [WebSocketListener]
         * that forwards events to this channel.
         *
         * Returns `true` if the stream completed normally ([StreamEvent.Done]) and
         * no reconnect should be attempted; `false` if the connection failed or
         * closed unexpectedly.
         */
        suspend fun openConnection(): Boolean {
            var completedNormally = false

            val url = "$WS_BASE_URL/ws/chat/$conversationId?token=$jwt"
            val request = Request.Builder().url(url).build()

            val listener = object : WebSocketListener() {

                override fun onOpen(ws: WebSocket, response: Response) {
                    webSocket = ws
                    attempt = 0 // reset backoff counter on successful connection
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    val event = parseEvent(text)
                    trySend(event)
                    if (event is StreamEvent.Done) {
                        completedNormally = true
                        // Close the channel normally â€” no reconnect
                        close()
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    // If channel is still open, let the outer loop decide whether to retry
                    webSocket = null
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    webSocket = null
                }
            }

            val ws = okHttpClient.newWebSocket(request, listener)
            webSocket = ws

            // Block until the channel is closed (either normally via Done, or by cancellation)
            awaitClose {
                ws.close(1000, "Client disconnect")
                webSocket = null
            }

            return completedNormally
        }

        // â”€â”€ Reconnect loop â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        while (isActive) {
            val done = openConnection()

            if (done) {
                // Stream completed normally â€” do not reconnect
                break
            }

            attempt++
            if (attempt >= MAX_RECONNECT_ATTEMPTS) {
                trySend(StreamEvent.Error("Max reconnect attempts exceeded"))
                close()
                break
            }

            // Exponential backoff: 1s, 2s, 4s, 8s, 16s (capped at 30s)
            val backoffMs = minOf(INITIAL_BACKOFF_MS shl (attempt - 1), MAX_BACKOFF_MS)
            withContext(dispatcherProvider.io) {
                delay(backoffMs)
            }
        }
    }

    /**
     * Serialises [payload] to JSON and sends it over the active WebSocket.
     * No-op if the connection is not currently open.
     */
    override fun sendMessage(payload: MessagePayload) {
        val ws = webSocket ?: return
        val jsonString = Json.encodeToString(MessagePayload.serializer(), payload)
        ws.send(jsonString)
    }

    /**
     * Closes the active WebSocket with a normal closure code and clears the reference.
     * Safe to call from any thread; subsequent [sendMessage] calls are no-ops.
     */
    override fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    // â”€â”€â”€ Event parsing â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Parses a raw WebSocket text frame into the appropriate [StreamEvent] subtype.
     *
     * Expected frame formats (Requirement 26.5):
     * - `{"type":"token",     "data":"<text>"}`
     * - `{"type":"done",      "usage":{"inputTokens":N,"outputTokens":N}}`
     * - `{"type":"error",     "message":"<message>"}`
     * - `{"type":"tool_call", "toolName":"<name>","toolInput":{...}}`
     *
     * Any malformed or unrecognised frame becomes [StreamEvent.Error].
     */
    internal fun parseEvent(rawText: String): StreamEvent {
        return try {
            val root: JsonObject = json.parseToJsonElement(rawText).jsonObject
            when (val type = root["type"]?.jsonPrimitive?.content) {
                "token" -> {
                    val data = root["data"]?.jsonPrimitive?.content
                        ?: return StreamEvent.Error("Unrecognized event: $rawText")
                    StreamEvent.Token(text = data)
                }

                "done" -> {
                    val usageObj = root["usage"]?.jsonObject
                        ?: return StreamEvent.Error("Unrecognized event: $rawText")
                    val inputTokens = usageObj["inputTokens"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: return StreamEvent.Error("Unrecognized event: $rawText")
                    val outputTokens = usageObj["outputTokens"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: return StreamEvent.Error("Unrecognized event: $rawText")
                    StreamEvent.Done(usage = TokenUsage(inputTokens, outputTokens))
                }

                "error" -> {
                    val message = root["message"]?.jsonPrimitive?.content
                        ?: return StreamEvent.Error("Unrecognized event: $rawText")
                    StreamEvent.Error(message = message)
                }

                "tool_call" -> {
                    val toolName = root["toolName"]?.jsonPrimitive?.content
                        ?: return StreamEvent.Error("Unrecognized event: $rawText")
                    val toolInput = root["toolInput"]?.jsonObject
                        ?: return StreamEvent.Error("Unrecognized event: $rawText")
                    StreamEvent.ToolCall(toolName = toolName, toolInput = toolInput)
                }

                else -> StreamEvent.Error("Unrecognized event: $rawText")
            }
        } catch (e: Exception) {
            StreamEvent.Error("Unrecognized event: $rawText")
        }
    }
}
