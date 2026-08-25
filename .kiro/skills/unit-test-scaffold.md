# Skill: Unit Test Scaffold

## Purpose
Write well-structured unit and instrumented tests for the Android AI Assistant project,
using the exact test stack already configured: JUnit 4, MockK, Kotest assertions,
Turbine (Flow testing), `kotlinx-coroutines-test`, MockWebServer, and Room Testing.

## When to Use
- Writing a ViewModel test with `StateFlow` assertions
- Writing a Use Case test that validates business rules
- Writing a Repository test against `MockWebServer` (remote) or an in-memory Room DB (local)
- Writing a Compose UI test with Hilt injection
- Writing a FastAPI endpoint integration test (pytest)

---

## Test Stack Reference

| Library | Version | Use |
|---|---|---|
| JUnit 4 | 4.13.2 | Base test runner |
| MockK | 1.13.12 | Kotlin-first mocking |
| Kotest assertions | 5.9.1 | Fluent assertions (`shouldBe`, `shouldContain`, etc.) |
| Turbine | 1.1.0 | `Flow` / `StateFlow` testing |
| kotlinx-coroutines-test | 1.9.0 | `runTest`, `TestCoroutineDispatcher` |
| arch-core-testing | 2.2.0 | `InstantTaskExecutorRule` |
| MockWebServer | 4.12.0 | Fake HTTP/WebSocket server |
| Room Testing | 2.6.1 | In-memory Room DB |
| Robolectric | 4.13 | Android APIs in JVM |
| Kotest property | 5.9.1 | Property-based tests |

---

## Pattern 1 — ViewModel Test

```kotlin
// feature-chat/src/test/kotlin/com/aiassistant/feature/chat/ChatDetailViewModelTest.kt
package com.aiassistant.feature.chat

import app.cash.turbine.test
import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.ai.StreamEvent
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.TestDispatcherProvider  // or your real impl
import com.aiassistant.domain.usecase.conversation.SendMessageUseCase
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.arch.core.executor.testing.InstantTaskExecutorRule

class ChatDetailViewModelTest {

    @get:Rule val instantTaskRule = InstantTaskExecutorRule()

    private val sendMessageUseCase: SendMessageUseCase = mockk()
    private val streamClient: AIStreamClient = mockk()
    private val dispatchers = TestDispatcherProvider()  // wraps UnconfinedTestDispatcher

    private lateinit var viewModel: ChatDetailViewModel

    @Before
    fun setUp() {
        // Provide a fake SavedStateHandle with the required conversationId arg
        val handle = androidx.lifecycle.SavedStateHandle(mapOf("conversationId" to "conv-123"))

        every { sendMessageUseCase.invoke(any(), any(), any()) } returns
            ApiResult.Success(Unit)

        viewModel = ChatDetailViewModel(
            savedStateHandle = handle,
            sendMessageUseCase = sendMessageUseCase,
            // ... other use cases mocked with relaxed = true
            streamClient = streamClient,
            dispatchers = dispatchers,
            getContextSuggestionsUseCase = mockk(relaxed = true),
            regenerateMessageUseCase = mockk(relaxed = true),
            exportConversationUseCase = mockk(relaxed = true),
        )
    }

    @Test
    fun `sendMessage appends user message optimistically`() = runTest {
        val tokenFlow = flowOf(
            StreamEvent.Token("Hello"),
            StreamEvent.Token(" world"),
            StreamEvent.Done(usage = mockk(relaxed = true)),
        )
        every { streamClient.connect(any(), any()) } returns tokenFlow
        every { streamClient.sendMessage(any()) } just Runs

        viewModel.uiState.test {
            viewModel.sendMessage("Hi there")

            // Initial state
            awaitItem() // loading / typing

            // After streaming completes
            val finalState = awaitItem()
            finalState.messages.any { it.content == "Hello world" && it.role == "assistant" } shouldBe true
            finalState.isStreaming shouldBe false

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `streaming error sets showRetryOption`() = runTest {
        val errorFlow = flowOf(StreamEvent.Error("Server unavailable"))
        every { streamClient.connect(any(), any()) } returns errorFlow
        every { streamClient.sendMessage(any()) } just Runs

        viewModel.uiState.test {
            viewModel.sendMessage("test")
            // consume intermediate states
            skipItems(2)
            val errorState = awaitItem()
            errorState.showRetryOption shouldBe true
            errorState.isStreaming shouldBe false
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

### `TestDispatcherProvider`

Create once in `:core-common` under `src/test/`:

```kotlin
package com.aiassistant.core.common

import kotlinx.coroutines.test.UnconfinedTestDispatcher

class TestDispatcherProvider : DispatcherProvider {
    val testDispatcher = UnconfinedTestDispatcher()
    override val main get() = testDispatcher
    override val io   get() = testDispatcher
    override val default get() = testDispatcher
}
```

---

## Pattern 2 — Use Case Test

```kotlin
// domain/src/test/kotlin/com/aiassistant/domain/usecase/SendMessageUseCaseTest.kt
package com.aiassistant.domain.usecase

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.ConversationRepository
import com.aiassistant.domain.usecase.conversation.SendMessageUseCase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SendMessageUseCaseTest {

    private val repository: ConversationRepository = mockk()
    private val useCase = SendMessageUseCase(repository)

    @Test
    fun `returns success when repository succeeds`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any()) } returns ApiResult.Success(Unit)

        val result = useCase("conv-1", "Hello", "openai_gpt4o")

        result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
        coVerify(exactly = 1) { repository.sendMessage("conv-1", "Hello", "openai_gpt4o") }
    }

    @Test
    fun `returns error when repository throws`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any()) } throws RuntimeException("Network error")

        val result = useCase("conv-1", "Hello", "openai_gpt4o")

        result.shouldBeInstanceOf<ApiResult.Error<*>>()
    }

    @Test
    fun `blank content is rejected immediately`() = runTest {
        val result = useCase("conv-1", "   ", "openai_gpt4o")

        result.shouldBeInstanceOf<ApiResult.Error<*>>()
        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any()) }
    }
}
```

---

## Pattern 3 — Room DAO Test (in-memory)

```kotlin
// core-database/src/test/kotlin/com/aiassistant/core/database/dao/MessageDaoTest.kt
package com.aiassistant.core.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.aiassistant.core.database.AppDatabase
import com.aiassistant.core.database.entity.MessageEntity
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()   // only for tests
            .build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `insert and observe messages by conversation`() = runTest {
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = "conv-1",
            role = "user",
            content = "Hello",
            createdAt = Instant.now(),
        )

        dao.upsert(entity)

        dao.observeByConversation("conv-1").test {
            val items = awaitItem()
            items shouldHaveSize 1
            items.first().content shouldBe "Hello"
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

---

## Pattern 4 — MockWebServer API Test

```kotlin
// data/src/test/kotlin/com/aiassistant/data/remote/ConversationApiTest.kt
package com.aiassistant.data.remote

import com.aiassistant.core.network.di.NetworkModule
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class ConversationApiTest {

    private val server = MockWebServer()
    private lateinit var api: ConversationApiService

    @Before
    fun setUp() {
        server.start()
        api = NetworkModule.provideRetrofit(
            client = /* test OkHttpClient without pinning */,
            json = NetworkModule.provideJson(),
        ).let { it.create(ConversationApiService::class.java) }
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `getConversations parses response correctly`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"conversations":[{"id":"conv-1","title":"Test"}]}""")
                .addHeader("Content-Type", "application/json")
        )

        val response = api.getConversations()

        response.conversations shouldNotBe null
        response.conversations.first().id shouldBe "conv-1"
    }
}
```

---

## Pattern 5 — Compose UI Test (Hilt)

```kotlin
// feature-chat/src/androidTest/kotlin/com/aiassistant/feature/chat/ChatDetailScreenTest.kt
package com.aiassistant.feature.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aiassistant.HiltTestActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class ChatDetailScreenTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Before fun setUp() = hiltRule.inject()

    @Test
    fun `send button is disabled when input is blank`() {
        composeRule.setContent {
            com.aiassistant.core.ui.AppTheme {
                ChatDetailScreenContent(
                    uiState = ChatDetailUiState(conversationId = "conv-1"),
                    onSendMessage = {},
                    onNavigateBack = {},
                    onRetry = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Send message")
            .assertIsNotEnabled()
    }
}
```

---

## Pattern 6 — Backend pytest (FastAPI)

```python
# backend/tests/unit/test_<domain>_service.py
import pytest
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from app.services.<domain>_service import <Domain>Service


@pytest.fixture
def mock_repo():
    return AsyncMock()


@pytest.fixture
def service(mock_repo):
    svc = <Domain>Service(db=MagicMock())
    svc._repo = mock_repo
    return svc


@pytest.mark.asyncio
async def test_get_or_404_raises_when_not_found(service, mock_repo):
    mock_repo.get_by_id.return_value = None
    from fastapi import HTTPException
    with pytest.raises(HTTPException) as exc:
        await service.get_or_404(uuid4(), uuid4())
    assert exc.value.status_code == 404
```

---

## JaCoCo Coverage Gate

The CI `jacoco-gate` job requires ≥ **70% combined coverage** for `:domain` and `:data`.
Run locally:

```bash
./gradlew :domain:testDebugUnitTest :data:testDebugUnitTest jacocoTestReport
# Report at domain/build/reports/jacoco/jacocoTestReport/html/index.html
```

---

## Checklist

- [ ] Arrange-Act-Assert structure in every test
- [ ] `runTest` used for every coroutine test (never `runBlocking` in tests)
- [ ] `Turbine.test { }` used for Flow/StateFlow assertions
- [ ] MockK `coEvery` / `coVerify` used for suspend functions
- [ ] `TestDispatcherProvider` injected into all ViewModels under test
- [ ] In-memory Room DB used (not the real DB) for DAO tests
- [ ] `MockWebServer` used (not the live server) for API tests
- [ ] At least one negative / error path tested per use case
- [ ] Coverage ≥ 70% maintained for `:domain` and `:data`
- [ ] No `Thread.sleep()` in tests — use `advanceUntilIdle()` or `awaitItem()`
