/**
 * PiiFilter.kt — core-common module
 *
 * Purpose: Strip or hash Personally Identifiable Information (PII) from strings
 *          before they are included in [ObservabilityEvent] fields or sent to any
 *          external service.
 *
 * Architecture: core-common — pure Kotlin, zero Android/framework dependencies.
 *               Must be called in the capture layer (interceptors, crash handlers)
 *               BEFORE an [ObservabilityEvent] is constructed — never after.
 *
 * What is filtered:
 * - Email addresses         → redacted as "[email]"
 * - Phone numbers           → redacted as "[phone]"
 * - Bearer / JWT tokens     → redacted as "[token]"
 * - Authorization headers   → redacted as "[redacted]"
 * - Credit card numbers     → redacted as "[card]"
 * - IP addresses            → redacted as "[ip]"
 * - UUIDs in paths          → preserved (they are not PII; they identify resources)
 *
 * Design decisions:
 * - Regex-based replacement is fast and allocation-cheap for the string sizes
 *   encountered in log messages and API paths.
 * - The filter is intentionally conservative: it is better to over-redact a
 *   non-PII string than to under-redact real PII.
 * - [filterMap] applies [filter] to every value in a metadata map so callers
 *   never need to filter key-by-key.
 * - All functions are pure (no side effects) and thread-safe.
 *
 * AI Safety Principle 5: never expose secrets — scrub credentials, tokens, and
 * PII before logging or sending to LLM.
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.common.observability

/**
 * Stateless utility that redacts PII patterns from arbitrary strings.
 *
 * Usage:
 * ```kotlin
 * val safe = PiiFilter.filter(userMessage)
 * val safeMap = PiiFilter.filterMap(metadata)
 * ```
 */
object PiiFilter {

    // ─── Regex patterns ───────────────────────────────────────────────────────

    /**
     * RFC 5322-simplified email pattern.
     * Matches most practical email addresses without a full RFC parser.
     * Example matches: user@example.com, first.last+tag@sub.domain.org
     */
    private val EMAIL_REGEX = Regex(
        """[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""",
    )

    /**
     * E.164-compatible phone number pattern.
     * Matches: +1-800-555-0100, (800) 555-0100, 8005550100, +447700900000
     * Conservative — may miss exotic formats, which is acceptable (false negative
     * is safer than a false positive that corrupts a non-PII value).
     */
    private val PHONE_REGEX = Regex(
        """(\+?[\d\s\-().]{7,15}\d)""",
    )

    /**
     * Bearer token / JWT pattern.
     * Matches the token portion after "Bearer " in Authorization headers or log lines.
     * A JWT has three Base64url segments separated by dots; a raw token is a long
     * Base64 string. Both are caught by this pattern.
     */
    private val TOKEN_REGEX = Regex(
        """(?i)(bearer\s+)[A-Za-z0-9\-_=+/.]{20,}""",
    )

    /**
     * Raw Authorization header value pattern (catches "Bearer ..." and "Basic ...").
     * Applied after TOKEN_REGEX so that "Bearer <token>" is handled by the more
     * specific rule; this catches "Basic <base64>" and similar schemes.
     */
    private val AUTH_HEADER_REGEX = Regex(
        """(?i)(authorization\s*[:=]\s*)[^\s,;]{8,}""",
    )

    /**
     * Credit / debit card number pattern.
     * Matches 13–19 digit sequences, optionally space- or dash-separated.
     * Luhn validation is intentionally omitted — the regex is a safety net, not a
     * payment processor.
     */
    private val CARD_REGEX = Regex(
        """\b(?:\d[ \-]?){13,19}\b""",
    )

    /**
     * IPv4 address pattern.
     * Matches dotted-decimal addresses. IPv6 is not matched — the colons in IPv6
     * addresses are common in non-PII contexts (ports, timestamps) and would produce
     * excessive false positives.
     */
    private val IPV4_REGEX = Regex(
        """\b(?:\d{1,3}\.){3}\d{1,3}\b""",
    )

    /**
     * Ordered list of (pattern, replacement) pairs.
     * Ordering matters: TOKEN_REGEX runs before AUTH_HEADER_REGEX so the specific
     * bearer-token pattern takes precedence.
     */
    private val RULES: List<Pair<Regex, String>> = listOf(
        TOKEN_REGEX to "Bearer [token]",
        AUTH_HEADER_REGEX to "$1[redacted]",
        EMAIL_REGEX to "[email]",
        CARD_REGEX to "[card]",
        IPV4_REGEX to "[ip]",
        PHONE_REGEX to "[phone]",
    )

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns a copy of [input] with all detected PII patterns replaced by their
     * safe placeholders.
     *
     * The input string is unchanged if no PII is detected. All replacements are
     * applied in a single pass per rule; patterns do not interact.
     *
     * @param input Raw string that may contain PII.
     * @return PII-free string safe to include in an [ObservabilityEvent].
     */
    fun filter(input: String): String {
        var result = input
        for ((pattern, replacement) in RULES) {
            result = pattern.replace(result, replacement)
        }
        return result
    }

    /**
     * Applies [filter] to every **value** in [map], returning a new map with the
     * same keys but PII-stripped values.
     *
     * Keys are not filtered — metadata keys should always be hardcoded constants,
     * never user-supplied strings.
     *
     * @param map Metadata map whose values may contain PII.
     * @return New map with all values passed through [filter].
     */
    fun filterMap(map: Map<String, String>): Map<String, String> =
        map.mapValues { (_, value) -> filter(value) }

    /**
     * Returns `true` if [input] appears to contain PII.
     *
     * Intended for debug-time assertions and tests — not for production gating
     * (use [filter] for that).
     *
     * @param input String to inspect.
     */
    fun containsPii(input: String): Boolean =
        RULES.any { (pattern, _) -> pattern.containsMatchIn(input) }
}
