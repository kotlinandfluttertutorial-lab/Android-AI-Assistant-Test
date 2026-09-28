/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ImageAgentTest.kt
 * Purpose    : Unit tests for ImageAgent — OCR, vision, missing image,
 *              remote data source errors, bounding box normalisation.
 *
 * Architecture Layer : Data — agent sub-package (test)
 * Pattern Used       : JUnit4 + MockK + Kotest
 * ============================================================
 */
package com.aiassistant.data.agent

import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.data.repository.TestDispatcherProvider
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.ImageAnalysisResult
import com.aiassistant.domain.agent.NormalisedBoundingBox
import com.aiassistant.domain.network.ImageAnalysisRemoteDataSource
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ImageAgentTest {

    private lateinit var remoteDataSource: ImageAnalysisRemoteDataSource
    private lateinit var agent: ImageAgent

    @Before
    fun setUp() {
        remoteDataSource = mockk(relaxed = true)
        agent = ImageAgent(remoteDataSource, TestDispatcherProvider())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val sampleB64 = "aW1hZ2VieXRlcw=="   // base64 of "imagebytes"

    private fun ocrResult(
        text: String = "Hello OCR",
        noText: Boolean = false,
        boxes: List<NormalisedBoundingBox> = emptyList(),
    ) = ImageAnalysisResult(
        extractedText = text,
        boundingBoxes = boxes,
        noTextFound = noText,
        visionAnalysis = null,
    )

    private fun visionResult(visionText: String = "A cat on a mat") =
        ImageAnalysisResult(
            extractedText = "",
            boundingBoxes = emptyList(),
            noTextFound = true,
            visionAnalysis = visionText,
        )

    private fun req(
        action: String = "ocr",
        imageBase64: String? = sampleB64,
        imageUri: String? = null,
        prompt: String? = null,
    ) = AgentRequest(
        userId = "user-1",
        input = prompt ?: "Analyse image.",
        metadata = buildMap {
            put(ImageAgent.METADATA_AGENT_NAME, ImageAgent.NAME)
            put(ImageAgent.METADATA_IMAGE_ACTION, action)
            imageBase64?.let { put(ImageAgent.METADATA_IMAGE_BASE64, it) }
            imageUri?.let { put(ImageAgent.METADATA_IMAGE_URI, it) }
            prompt?.let { put(ImageAgent.METADATA_PROMPT, it) }
        },
    )

    private fun exec(r: AgentRequest = req()) =
        AgentExecution(request = r, agentName = ImageAgent.NAME)

    // ── Metadata / capabilities ───────────────────────────────────────────────

    @Test
    fun `agent name is image-analysis`() {
        agent.name shouldBe ImageAgent.NAME
    }

    @Test
    fun `declares IMAGE_UNDERSTANDING capability`() {
        (AgentCapability.IMAGE_UNDERSTANDING in agent.capabilities).shouldBeTrue()
    }

    @Test
    fun `canHandle returns true when agent_name matches`() {
        agent.canHandle(req()).shouldBeTrue()
    }

    @Test
    fun `canHandle returns true for IMAGE_UNDERSTANDING capability`() {
        val r = AgentRequest(
            userId = "u1", input = "scan",
            capabilities = setOf(AgentCapability.IMAGE_UNDERSTANDING),
        )
        agent.canHandle(r).shouldBeTrue()
    }

    // ── Missing image ─────────────────────────────────────────────────────────

    @Test
    fun `missing both image_base64 and image_uri emits MISSING_IMAGE`() = runTest {
        val r = req(imageBase64 = null, imageUri = null)
        val events = agent.execute(r, exec(r)).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "MISSING_IMAGE"
    }

    // ── OCR success ───────────────────────────────────────────────────────────

    @Test
    fun `OCR success emits Started, StatusChanged, Thinking, Token, Completed`() = runTest {
        coEvery { remoteDataSource.analyzeImage(any(), any(), any(), any(), any(), any()) } returns
            ocrResult("Extracted text here")

        val events = agent.execute(req(), exec()).toList()

        events[0].shouldBeInstanceOf<AgentEvent.Started>()
        events[1].shouldBeInstanceOf<AgentEvent.StatusChanged>()
        events.filterIsInstance<AgentEvent.Thinking>().shouldNotBeEmpty()
        val token = events.filterIsInstance<AgentEvent.Token>().first()
        token.token.contains("Extracted text here").shouldBeTrue()
        events.filterIsInstance<AgentEvent.Completed>().first()
            .result.status shouldBe AgentStatus.COMPLETED
    }

    @Test
    fun `OCR no-text result emits message indicating no text detected`() = runTest {
        coEvery { remoteDataSource.analyzeImage(any(), any(), any(), any(), any(), any()) } returns
            ocrResult(text = "", noText = true)

        val events = agent.execute(req(), exec()).toList()

        val token = events.filterIsInstance<AgentEvent.Token>().first()
        token.token.contains("No text").shouldBeTrue()
    }

    @Test
    fun `bounding box count is included in OCR token when boxes present`() = runTest {
        val boxes = listOf(
            NormalisedBoundingBox(text = "Hello", left = 0f, top = 0f, right = 0.2f, bottom = 0.05f),
            NormalisedBoundingBox(text = "World", left = 0.3f, top = 0f, right = 0.6f, bottom = 0.05f),
        )
        coEvery { remoteDataSource.analyzeImage(any(), any(), any(), any(), any(), any()) } returns
            ocrResult(text = "Hello World", boxes = boxes)

        val events = agent.execute(req(), exec()).toList()

        val token = events.filterIsInstance<AgentEvent.Token>().first()
        token.token.contains("2").shouldBeTrue()
    }

    // ── Vision success ────────────────────────────────────────────────────────

    @Test
    fun `vision action returns visionAnalysis in token`() = runTest {
        coEvery { remoteDataSource.analyzeImage(any(), any(), any(), any(), any(), any()) } returns
            visionResult("A cat sitting on a mat.")

        val r = req(action = "vision", prompt = "What is in this image?")
        val events = agent.execute(r, exec(r)).toList()

        val token = events.filterIsInstance<AgentEvent.Token>().first()
        token.token shouldBe "A cat sitting on a mat."
    }

    @Test
    fun `vision action forwards prompt to remote data source`() = runTest {
        coEvery { remoteDataSource.analyzeImage(any(), any(), any(), any(), any(), any()) } returns
            visionResult()

        val r = req(action = "vision", prompt = "What colour is the sky?")
        agent.execute(r, exec(r)).toList()

        coVerify {
            remoteDataSource.analyzeImage(
                imageBase64 = any(),
                imageUri = any(),
                prompt = "What colour is the sky?",
                provider = any(),
                imageWidth = any(),
                imageHeight = any(),
            )
        }
    }

    // ── Remote data source error ──────────────────────────────────────────────

    @Test
    fun `remote data source exception emits ANALYSIS_ERROR`() = runTest {
        coEvery { remoteDataSource.analyzeImage(any(), any(), any(), any(), any(), any()) } throws
            RuntimeException("HTTP 503")

        val events = agent.execute(req(), exec()).toList()

        events.filterIsInstance<AgentEvent.Failed>().first()
            .result.error?.code shouldBe "ANALYSIS_ERROR"
    }

    // ── Image URI fallback ────────────────────────────────────────────────────

    @Test
    fun `image_uri is forwarded to remote data source when base64 is absent`() = runTest {
        coEvery { remoteDataSource.analyzeImage(any(), any(), any(), any(), any(), any()) } returns
            ocrResult()

        val r = req(imageBase64 = null, imageUri = "content://media/1234")
        agent.execute(r, exec(r)).toList()

        coVerify {
            remoteDataSource.analyzeImage(
                imageBase64 = null,
                imageUri = "content://media/1234",
                prompt = any(),
                provider = any(),
                imageWidth = any(),
                imageHeight = any(),
            )
        }
    }

    // ── Completed result metadata ─────────────────────────────────────────────

    @Test
    fun `completed result metadata contains action`() = runTest {
        coEvery { remoteDataSource.analyzeImage(any(), any(), any(), any(), any(), any()) } returns
            ocrResult()

        val events = agent.execute(req(), exec()).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.metadata["action"] shouldBe "ocr"
    }
}
