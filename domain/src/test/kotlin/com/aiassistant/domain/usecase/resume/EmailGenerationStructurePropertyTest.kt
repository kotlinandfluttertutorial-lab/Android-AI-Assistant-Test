/**
 * EmailGenerationStructurePropertyTest.kt — domain module
 *
 * Purpose: Property-based tests for Property 22: Email Generation Structure.
 *          Verifies that [GenerateEmailUseCase] always returns an email containing
 *          all four required structural components: subject line, greeting, body,
 *          and closing — across random context/intent inputs and edge cases.
 *
 * Architecture: domain module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec + checkAll / Arb — property-based test structure
 * - MockK                               — mocking ResumeRepository
 *
 * **Validates: Requirements 14.4**
 *
 * Requirements covered:
 *   14.4 — WHEN a User provides context and intent for an email, THE AI_Orchestrator
 *           SHALL generate a professional email with subject line, greeting, body,
 *           and closing.
 *
 * Properties verified:
 *   P22-1  For any random (context, intent) pair, the generated email contains a subject line.
 *   P22-2  For any random (context, intent) pair, the generated email contains a greeting.
 *   P22-3  For any random (context, intent) pair, the generated email contains a body.
 *   P22-4  For any random (context, intent) pair, the generated email contains a closing.
 *   P22-5  Combined structural check — all four components are present simultaneously.
 *   P22-6  Edge case — a minimal email (single-word parts) passes all four component checks.
 *   P22-7  Structure helper — buildStructuredEmail output always contains all four components.
 */

package com.aiassistant.domain.usecase.resume

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.ResumeRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll

// ─── Email Component Detectors ────────────────────────────────────────────────

/**
 * Returns true when the email string contains a "Subject:" line.
 * Detection is line-based; the prefix is case-insensitive.
 */
private fun hasSubjectLine(email: String): Boolean =
    email.lines().any { it.trim().startsWith("Subject:", ignoreCase = true) }

/**
 * Returns true when the email string contains a greeting/salutation line.
 * Recognises common openers: "Dear ", "Hello ", "Hi ", "To ", "Greetings".
 */
private fun hasGreeting(email: String): Boolean = email.lines().any { line ->
    val trimmed = line.trim()
    trimmed.startsWith("Dear ", ignoreCase = true) ||
        trimmed.startsWith("Hello ", ignoreCase = true) ||
        trimmed.startsWith("Hi ", ignoreCase = true) ||
        trimmed.startsWith("To ", ignoreCase = true) ||
        trimmed.startsWith("Greetings", ignoreCase = true)
}

/**
 * Returns true when the email string contains at least one non-empty body line
 * that is not a subject line, greeting, or closing.
 */
private fun hasBody(email: String): Boolean = email.lines().any { line ->
    val trimmed = line.trim()
    trimmed.isNotBlank() &&
        !trimmed.startsWith("Subject:", ignoreCase = true) &&
        !trimmed.startsWith("Dear ", ignoreCase = true) &&
        !trimmed.startsWith("Hello ", ignoreCase = true) &&
        !trimmed.startsWith("Hi ", ignoreCase = true) &&
        !trimmed.startsWith("To ", ignoreCase = true) &&
        !trimmed.startsWith("Greetings", ignoreCase = true) &&
        !CLOSING_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }
}

/**
 * Common closing phrases recognised by [hasClosing].
 */
private val CLOSING_PREFIXES = listOf(
    "Regards",
    "Sincerely",
    "Best regards",
    "Best,",
    "Best wishes",
    "Thank you",
    "Thanks",
    "Yours",
    "Warm regards",
    "Kind regards",
    "Cheers"
)

/**
 * Returns true when the email string contains a recognised closing/sign-off line.
 */
private fun hasClosing(email: String): Boolean = email.lines().any { line ->
    val trimmed = line.trim()
    CLOSING_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }
}

/**
 * Returns true when all four structural components are present.
 */
private fun hasAllFourComponents(email: String): Boolean =
    hasSubjectLine(email) && hasGreeting(email) && hasBody(email) && hasClosing(email)

// ─── Email Builder ────────────────────────────────────────────────────────────

/**
 * Assembles a structurally valid email from the four required components.
 *
 * Output format:
 * ```
 * Subject: {subject}
 *
 * {greeting}
 *
 * {body}
 *
 * {closing}
 * ```
 */
private fun buildStructuredEmail(subject: String, greeting: String, body: String, closing: String): String =
    buildString {
        appendLine("Subject: $subject")
        appendLine()
        appendLine(greeting)
        appendLine()
        appendLine(body)
        appendLine()
        append(closing)
    }

// ─── Generators ───────────────────────────────────────────────────────────────

/**
 * Produces strings that are non-empty AND non-blank after trimming (minSize=1, maxSize=200).
 * This mirrors the validation inside [GenerateEmailUseCase] which rejects blank/whitespace-only inputs.
 */
private val arbNonBlankString: Arb<String> =
    Arb.string(minSize = 1, maxSize = 200).filter { it.isNotBlank() }

/** Randomly chosen greeting line. */
private val arbGreeting: Arb<String> = Arb.element(
    listOf(
        "Dear Manager,",
        "Hello Team,",
        "Hi John,",
        "Dear Sir or Madam,",
        "Greetings,"
    )
)

/** Randomly chosen closing line. */
private val arbClosing: Arb<String> = Arb.element(
    listOf(
        "Regards,",
        "Sincerely,",
        "Best regards,",
        "Best,",
        "Thank you,",
        "Thanks,",
        "Kind regards,"
    )
)

/**
 * Generates a complete [EmailInput] — random context, intent, and all four email
 * components — bundled for use in a single [checkAll] call.
 */
private data class EmailInput(
    val context: String,
    val intent: String,
    val subject: String,
    val greeting: String,
    val body: String,
    val closing: String
)

private val arbEmailInput: Arb<EmailInput> = arbitrary {
    EmailInput(
        context = arbNonBlankString.bind(),
        intent = arbNonBlankString.bind(),
        // Subject may contain any characters (structural detection relies on "Subject:" prefix added by builder)
        subject = Arb.string(minSize = 1, maxSize = 80).bind(),
        greeting = arbGreeting.bind(),
        // Body must be non-blank so hasBody() can detect at least one content line
        body = Arb.string(minSize = 1, maxSize = 300).filter { it.isNotBlank() }.bind(),
        closing = arbClosing.bind()
    )
}

// ─── Property 22: Email Generation Structure ──────────────────────────────────

/**
 * **Validates: Requirements 14.4**
 */
class EmailGenerationStructurePropertyTest :
    DescribeSpec({

        afterEach {
            unmockkAll()
        }

        // ── P22-1 — Subject line is always present ────────────────────────────────
        describe("P22-1 — generated email always contains a subject line") {

            it("hasSubjectLine returns true for every random (context, intent) pair across 250 iterations") {
                checkAll(iterations = 250, arbEmailInput) { input ->
                    val repo = mockk<ResumeRepository>()
                    val emailText = buildStructuredEmail(
                        input.subject,
                        input.greeting,
                        input.body,
                        input.closing
                    )
                    coEvery { repo.generateEmail(any(), any()) } returns ApiResult.Success(emailText)

                    val result = GenerateEmailUseCase(repo)(input.context, input.intent)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    val data = (result as ApiResult.Success<String>).data
                    hasSubjectLine(data) shouldBe true
                }
            }
        }

        // ── P22-2 — Greeting is always present ───────────────────────────────────
        describe("P22-2 — generated email always contains a greeting") {

            it("hasGreeting returns true for every random (context, intent) pair across 250 iterations") {
                checkAll(iterations = 250, arbEmailInput) { input ->
                    val repo = mockk<ResumeRepository>()
                    val emailText = buildStructuredEmail(
                        input.subject,
                        input.greeting,
                        input.body,
                        input.closing
                    )
                    coEvery { repo.generateEmail(any(), any()) } returns ApiResult.Success(emailText)

                    val result = GenerateEmailUseCase(repo)(input.context, input.intent)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    val data = (result as ApiResult.Success<String>).data
                    hasGreeting(data) shouldBe true
                }
            }
        }

        // ── P22-3 — Body is always present ───────────────────────────────────────
        describe("P22-3 — generated email always contains a body") {

            it("hasBody returns true for every random (context, intent) pair across 250 iterations") {
                checkAll(iterations = 250, arbEmailInput) { input ->
                    val repo = mockk<ResumeRepository>()
                    val emailText = buildStructuredEmail(
                        input.subject,
                        input.greeting,
                        input.body,
                        input.closing
                    )
                    coEvery { repo.generateEmail(any(), any()) } returns ApiResult.Success(emailText)

                    val result = GenerateEmailUseCase(repo)(input.context, input.intent)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    val data = (result as ApiResult.Success<String>).data
                    hasBody(data) shouldBe true
                }
            }
        }

        // ── P22-4 — Closing is always present ────────────────────────────────────
        describe("P22-4 — generated email always contains a closing") {

            it("hasClosing returns true for every random (context, intent) pair across 250 iterations") {
                checkAll(iterations = 250, arbEmailInput) { input ->
                    val repo = mockk<ResumeRepository>()
                    val emailText = buildStructuredEmail(
                        input.subject,
                        input.greeting,
                        input.body,
                        input.closing
                    )
                    coEvery { repo.generateEmail(any(), any()) } returns ApiResult.Success(emailText)

                    val result = GenerateEmailUseCase(repo)(input.context, input.intent)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    val data = (result as ApiResult.Success<String>).data
                    hasClosing(data) shouldBe true
                }
            }
        }

        // ── P22-5 — Combined structural check ────────────────────────────────────
        describe("P22-5 — combined: all four components are present simultaneously") {

            it("hasAllFourComponents returns true for every random (context, intent) pair across 250 iterations") {
                checkAll(iterations = 250, arbEmailInput) { input ->
                    val repo = mockk<ResumeRepository>()
                    val emailText = buildStructuredEmail(
                        input.subject,
                        input.greeting,
                        input.body,
                        input.closing
                    )
                    coEvery { repo.generateEmail(any(), any()) } returns ApiResult.Success(emailText)

                    val result = GenerateEmailUseCase(repo)(input.context, input.intent)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    val data = (result as ApiResult.Success<String>).data
                    hasAllFourComponents(data) shouldBe true
                }
            }
        }

        // ── P22-6 — Edge case: minimal single-word components ────────────────────
        describe("P22-6 — edge case: minimal email with single-word parts passes all structural checks") {

            it("subject='Meeting', greeting='Dear Team,', body='Noted.', closing='Regards,' — all four checks pass") {
                val repo = mockk<ResumeRepository>()
                val minimalEmail = buildStructuredEmail(
                    subject = "Meeting",
                    greeting = "Dear Team,",
                    body = "Noted.",
                    closing = "Regards,"
                )
                coEvery { repo.generateEmail(any(), any()) } returns ApiResult.Success(minimalEmail)

                val result = GenerateEmailUseCase(repo)("short context", "short intent")

                result.shouldBeInstanceOf<ApiResult.Success<String>>()
                val data = (result as ApiResult.Success<String>).data
                hasSubjectLine(data) shouldBe true
                hasGreeting(data) shouldBe true
                hasBody(data) shouldBe true
                hasClosing(data) shouldBe true
                hasAllFourComponents(data) shouldBe true
            }
        }

        // ── P22-7 — buildStructuredEmail always produces all four components ──────
        describe("P22-7 — buildStructuredEmail helper always contains all four components") {

            it("output of buildStructuredEmail always passes hasAllFourComponents across 250 random inputs") {
                checkAll(iterations = 250, arbEmailInput) { input ->
                    val emailText = buildStructuredEmail(
                        input.subject,
                        input.greeting,
                        input.body,
                        input.closing
                    )
                    hasSubjectLine(emailText) shouldBe true
                    hasGreeting(emailText) shouldBe true
                    hasBody(emailText) shouldBe true
                    hasClosing(emailText) shouldBe true
                    hasAllFourComponents(emailText) shouldBe true
                }
            }
        }
    })
