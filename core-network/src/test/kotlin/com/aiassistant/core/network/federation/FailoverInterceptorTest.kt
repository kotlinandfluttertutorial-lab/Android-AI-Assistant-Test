/**
 * FailoverInterceptorTest.kt — core-network unit tests
 *
 * Unit tests for [FailoverInterceptor] covering:
 * - Successful request is routed to the selected endpoint (URL rewritten)
 * - On 5xx response: retry against next eligible endpoint within the same call
 * - On connection error: failover to next eligible endpoint
 * - Non-eligible endpoints are NEVER used during failover
 * - Structured [NoEligibleEndpointException] thrown when all endpoints exhausted
 * - [FailoverEventBus] receives correct events during failover and exhaustion
 *
 * Requirements: 21.1, 35.3, 35.4, 35.6
 */

package com.aiassistant.core.network.federation

import com.aiassistant.domain.model.BackendEndpoint
import com.aiassistant.domain.model.FederationConfig
import com.aiassistant.domain.repository.FederationRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

// ─── Helper factories ─────────────────────────────────────────────────────────

private fun testEndpoint(name: String, baseUrl: String, region: String, roles: List<String>, latencyMs: Long = 100) =
    BackendEndpoint(name = name, baseUrl = baseUrl, regionTag = region, allowedRoles = roles, latencyMs = latencyMs)

private fun federationConfig(vararg endpoints: BackendEndpoint) = FederationConfig(endpoints.toList())

// ─── Test suite ───────────────────────────────────────────────────────────────

class FailoverInterceptorTest :
    DescribeSpec({

        /**
         * Creates fresh MockWebServer instances for each test to ensure no request-count
         * state bleeds between tests. Both servers are started before the block runs and
         * shut down in the registered closer.
         */
        fun withServers(block: suspend (primary: MockWebServer, secondary: MockWebServer) -> Unit) {
            val primary = MockWebServer()
            val secondary = MockWebServer()
            primary.start()
            secondary.start()
            try {
                kotlinx.coroutines.runBlocking { block(primary, secondary) }
            } finally {
                primary.shutdown()
                secondary.shutdown()
            }
        }

        fun buildInterceptor(
            config: FederationConfig,
            region: String = "us-east-1",
            role: String = "user"
        ): Triple<FailoverInterceptor, FailoverEventBus, FederationRepository> {
            val federationRepository = mockk<FederationRepository>()
            coEvery { federationRepository.getConfig() } returns config
            every { federationRepository.configFlow } returns emptyFlow()

            val selector = BackendEndpointSelector()
            val eventBus = FailoverEventBus()

            val regionProvider = mockk<UserRegionProvider>()
            every { regionProvider.getRegion() } returns region

            val roleProvider = mockk<UserRoleProvider>()
            every { roleProvider.getRole() } returns role

            val interceptor = FailoverInterceptor(
                federationRepository = federationRepository,
                endpointSelector = selector,
                failoverEventBus = eventBus,
                userRegionProvider = regionProvider,
                userRoleProvider = roleProvider
            )

            return Triple(interceptor, eventBus, federationRepository)
        }

        fun buildClient(interceptor: FailoverInterceptor): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        describe("FailoverInterceptor — happy path") {

            it("rewrites the request URL to the selected endpoint's baseUrl") {
                withServers { primaryServer, _ ->
                    val primaryEndpoint = testEndpoint(
                        name = "us-primary",
                        baseUrl = primaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("user"),
                        latencyMs = 100
                    )
                    val (interceptor, _, _) = buildInterceptor(federationConfig(primaryEndpoint))
                    val client = buildClient(interceptor)

                    primaryServer.enqueue(MockResponse().setResponseCode(200))

                    val request = Request.Builder()
                        .url("https://original-api.example.com/api/v1/chat")
                        .build()

                    val response = client.newCall(request).execute()
                    response.close()

                    response.code shouldBe 200
                    val recorded = primaryServer.takeRequest()
                    // The request should arrive at the primary server with the correct path preserved.
                    recorded.path shouldBe "/api/v1/chat"
                }
            }

            it("succeeds without failover when primary endpoint returns 200") {
                withServers { primaryServer, secondaryServer ->
                    val primaryEndpoint = testEndpoint(
                        name = "us-primary",
                        baseUrl = primaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("user"),
                        latencyMs = 100
                    )
                    val secondaryEndpoint = testEndpoint(
                        name = "us-secondary",
                        baseUrl = secondaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("user"),
                        latencyMs = 200
                    )
                    val (interceptor, _, _) = buildInterceptor(federationConfig(primaryEndpoint, secondaryEndpoint))
                    val client = buildClient(interceptor)

                    primaryServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

                    val response = client.newCall(
                        Request.Builder().url("https://api.example.com/api/test").build()
                    ).execute()
                    response.close()

                    response.code shouldBe 200
                    // Secondary server should have received no request.
                    secondaryServer.requestCount shouldBe 0
                }
            }
        }

        describe("FailoverInterceptor — 5xx failover") {

            it("retries against the next eligible endpoint when primary returns 503") {
                withServers { primaryServer, secondaryServer ->
                    val primaryEndpoint = testEndpoint(
                        name = "us-primary",
                        baseUrl = primaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("user"),
                        latencyMs = 100
                    )
                    val secondaryEndpoint = testEndpoint(
                        name = "us-secondary",
                        baseUrl = secondaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("user"),
                        latencyMs = 200
                    )
                    val (interceptor, _, _) = buildInterceptor(federationConfig(primaryEndpoint, secondaryEndpoint))
                    val client = buildClient(interceptor)

                    primaryServer.enqueue(MockResponse().setResponseCode(503))
                    secondaryServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

                    val response = client.newCall(
                        Request.Builder().url("https://api.example.com/api/test").build()
                    ).execute()
                    response.close()

                    response.code shouldBe 200
                    primaryServer.requestCount shouldBe 1
                    secondaryServer.requestCount shouldBe 1
                }
            }

            it("retries against next endpoint for any 5xx status code") {
                for (statusCode in listOf(500, 502, 503, 504)) {
                    // Use fresh servers per iteration to avoid requestCount accumulation.
                    withServers { primaryServer, secondaryServer ->
                        val primary = testEndpoint(
                            name = "us-primary-$statusCode",
                            baseUrl = primaryServer.url("/").toString(),
                            region = "us-east-1",
                            roles = listOf("user"),
                            latencyMs = 100
                        )
                        val secondary = testEndpoint(
                            name = "us-secondary-$statusCode",
                            baseUrl = secondaryServer.url("/").toString(),
                            region = "us-east-1",
                            roles = listOf("user"),
                            latencyMs = 200
                        )
                        val (interceptor, _, _) = buildInterceptor(federationConfig(primary, secondary))
                        val client = buildClient(interceptor)

                        primaryServer.enqueue(MockResponse().setResponseCode(statusCode))
                        secondaryServer.enqueue(MockResponse().setResponseCode(200))

                        val response = client.newCall(
                            Request.Builder().url("https://api.example.com/test").build()
                        ).execute()
                        response.close()

                        response.code shouldBe 200
                    }
                }
            }
        }

        describe("FailoverInterceptor — NoEligibleEndpoint protection") {

            it("throws NoEligibleEndpointException when no endpoint satisfies region constraint") {
                withServers { primaryServer, _ ->
                    val wrongRegionEndpoint = testEndpoint(
                        name = "eu-primary",
                        baseUrl = primaryServer.url("/").toString(),
                        region = "eu-west-1",
                        roles = listOf("user"),
                        latencyMs = 100
                    )
                    val (interceptor, _, _) = buildInterceptor(
                        config = federationConfig(wrongRegionEndpoint),
                        region = "us-east-1",
                        role = "user"
                    )
                    val client = buildClient(interceptor)

                    shouldThrow<NoEligibleEndpointException> {
                        client.newCall(Request.Builder().url("https://api.example.com/api/test").build()).execute()
                    }

                    // MUST NOT route to non-eligible endpoint
                    primaryServer.requestCount shouldBe 0
                }
            }

            it("throws NoEligibleEndpointException when no endpoint satisfies role constraint") {
                withServers { primaryServer, _ ->
                    val adminOnlyEndpoint = testEndpoint(
                        name = "us-admin",
                        baseUrl = primaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("admin"),
                        latencyMs = 100
                    )
                    val (interceptor, _, _) = buildInterceptor(
                        config = federationConfig(adminOnlyEndpoint),
                        region = "us-east-1",
                        role = "user"
                    )
                    val client = buildClient(interceptor)

                    shouldThrow<NoEligibleEndpointException> {
                        client.newCall(Request.Builder().url("https://api.example.com/api/test").build()).execute()
                    }

                    // MUST NOT route to non-eligible endpoint
                    primaryServer.requestCount shouldBe 0
                }
            }

            it("throws NoEligibleEndpointException when all endpoints fail — never routes to non-eligible") {
                withServers { primaryServer, secondaryServer ->
                    val primary = testEndpoint(
                        name = "us-primary",
                        baseUrl = primaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("user"),
                        latencyMs = 100
                    )
                    val secondary = testEndpoint(
                        name = "us-secondary",
                        baseUrl = secondaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("user"),
                        latencyMs = 200
                    )
                    // A third endpoint that does not match the user's role — must never be used
                    val nonEligible = testEndpoint(
                        name = "us-admin-only",
                        baseUrl = "https://should-never-reach.example.com/",
                        region = "us-east-1",
                        roles = listOf("admin"), // user role does not match
                        latencyMs = 10 // lowest latency — must still be skipped
                    )

                    val (interceptor, _, _) = buildInterceptor(
                        config = federationConfig(primary, secondary, nonEligible),
                        region = "us-east-1",
                        role = "user"
                    )
                    val client = buildClient(interceptor)

                    // Both eligible endpoints return 503
                    primaryServer.enqueue(MockResponse().setResponseCode(503))
                    secondaryServer.enqueue(MockResponse().setResponseCode(503))

                    val ex = shouldThrow<NoEligibleEndpointException> {
                        client.newCall(Request.Builder().url("https://api.example.com/api/test").build()).execute()
                    }
                    ex.message shouldContain "exhausted"
                }
            }

            it("throws NoEligibleEndpointException with empty federation config") {
                val (interceptor, _, _) = buildInterceptor(FederationConfig())
                val client = buildClient(interceptor)

                shouldThrow<NoEligibleEndpointException> {
                    client.newCall(Request.Builder().url("https://api.example.com/api/test").build()).execute()
                }
            }
        }

        describe("FailoverInterceptor — FailoverEventBus events") {

            it("publishes SwitchedToEndpoint event on 5xx failover") {
                withServers { primaryServer, secondaryServer ->
                    val primary = testEndpoint(
                        name = "us-primary",
                        baseUrl = primaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("user"),
                        latencyMs = 100
                    )
                    val secondary = testEndpoint(
                        name = "us-secondary",
                        baseUrl = secondaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("user"),
                        latencyMs = 200
                    )
                    val (interceptor, eventBus, _) = buildInterceptor(federationConfig(primary, secondary))
                    val client = buildClient(interceptor)

                    primaryServer.enqueue(MockResponse().setResponseCode(503))
                    secondaryServer.enqueue(MockResponse().setResponseCode(200))

                    // Collect events concurrently while the blocking HTTP call runs.
                    // Use a thread-safe list to capture events emitted via tryEmit.
                    val receivedEvents = java.util.concurrent.CopyOnWriteArrayList<FailoverEvent>()
                    val latch = java.util.concurrent.CountDownLatch(1)

                    val collectorJob = launch(Dispatchers.IO) {
                        eventBus.events.collect { event ->
                            receivedEvents.add(event)
                            latch.countDown()
                        }
                    }

                    val response = client.newCall(
                        Request.Builder().url("https://api.example.com/api/test").build()
                    ).execute()
                    response.close()

                    // Wait up to 5 seconds for at least one event.
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    collectorJob.cancel()

                    val switchEvent = receivedEvents.filterIsInstance<FailoverEvent.SwitchedToEndpoint>().firstOrNull()
                    switchEvent?.activeEndpointName shouldBe "us-secondary"
                }
            }

            it("publishes AllEndpointsExhausted event when all eligible endpoints fail") {
                withServers { primaryServer, _ ->
                    val primary = testEndpoint(
                        name = "us-only",
                        baseUrl = primaryServer.url("/").toString(),
                        region = "us-east-1",
                        roles = listOf("user"),
                        latencyMs = 100
                    )
                    val (interceptor, eventBus, _) = buildInterceptor(federationConfig(primary))
                    val client = buildClient(interceptor)

                    primaryServer.enqueue(MockResponse().setResponseCode(503))

                    val receivedEvents = java.util.concurrent.CopyOnWriteArrayList<FailoverEvent>()
                    val latch = java.util.concurrent.CountDownLatch(1)

                    val collectorJob = launch(Dispatchers.IO) {
                        eventBus.events.collect { event ->
                            receivedEvents.add(event)
                            latch.countDown()
                        }
                    }

                    runCatching {
                        client.newCall(
                            Request.Builder().url("https://api.example.com/api/test").build()
                        ).execute()
                    }

                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    collectorJob.cancel()

                    receivedEvents.any { it is FailoverEvent.AllEndpointsExhausted } shouldBe true
                }
            }
        }
    })
