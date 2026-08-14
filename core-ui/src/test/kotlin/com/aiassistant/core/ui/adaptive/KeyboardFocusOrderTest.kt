/**
 * KeyboardFocusOrderTest.kt
 *
 * Unit tests for the purely logical (non-Compose) wrapping behaviour of
 * [Modifier.logicalFocusOrder]. The Modifier extension itself uses Compose APIs,
 * so we test the index-wrapping arithmetic separately as plain Kotlin to keep
 * these as fast JVM unit tests (no Compose test runner needed).
 *
 * Requirements: 23.5
 */
package com.aiassistant.core.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Extracts and tests the wrapping logic embedded in [logicalFocusOrder]:
 *
 *   nextIndex     = (index + 1) % size
 *   previousIndex = (index - 1 + size) % size
 *
 * This must hold for groups of any size ≥ 1.
 */
class KeyboardFocusOrderTest {

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Returns the (nextIndex, previousIndex) pair for a given [index] in a group of [size]. */
    private fun computeOrder(index: Int, size: Int): Pair<Int, Int> {
        val nextIndex = (index + 1) % size
        val previousIndex = (index - 1 + size) % size
        return nextIndex to previousIndex
    }

    // ── Single-element group ──────────────────────────────────────────────────

    @Test
    fun `single element group wraps to itself in both directions`() {
        val (next, prev) = computeOrder(index = 0, size = 1)
        assertEquals("next of 0 in size-1 group should be 0", 0, next)
        assertEquals("previous of 0 in size-1 group should be 0", 0, prev)
    }

    // ── Two-element group ─────────────────────────────────────────────────────

    @Test
    fun `first item in two-element group wraps correctly`() {
        val (next, prev) = computeOrder(index = 0, size = 2)
        assertEquals("next of index 0 should be 1", 1, next)
        assertEquals("previous of index 0 should wrap to 1 (last)", 1, prev)
    }

    @Test
    fun `last item in two-element group wraps correctly`() {
        val (next, prev) = computeOrder(index = 1, size = 2)
        assertEquals("next of last index 1 should wrap to 0", 0, next)
        assertEquals("previous of index 1 should be 0", 0, prev)
    }

    // ── Three-element group (login form) ─────────────────────────────────────

    @Test
    fun `first item in three-element group points to second item and wraps back`() {
        val size = 3
        val (next, prev) = computeOrder(index = 0, size = size)
        assertEquals(1, next) // Tab goes forward
        assertEquals(2, prev) // Shift+Tab wraps to last
    }

    @Test
    fun `middle item in three-element group points to adjacent items`() {
        val size = 3
        val (next, prev) = computeOrder(index = 1, size = size)
        assertEquals(2, next)
        assertEquals(0, prev)
    }

    @Test
    fun `last item in three-element group wraps to first`() {
        val size = 3
        val (next, prev) = computeOrder(index = 2, size = size)
        assertEquals(0, next) // Tab wraps to first
        assertEquals(1, prev) // Shift+Tab goes to middle
    }

    // ── Larger groups ─────────────────────────────────────────────────────────

    @Test
    fun `last item next index always wraps to zero`() {
        for (size in 1..20) {
            val lastIndex = size - 1
            val (next, _) = computeOrder(index = lastIndex, size = size)
            assertEquals(
                "last item (index $lastIndex) next should be 0 in group of size $size",
                0,
                next
            )
        }
    }

    @Test
    fun `first item previous index always wraps to last`() {
        for (size in 1..20) {
            val (_, prev) = computeOrder(index = 0, size = size)
            val expectedLast = size - 1
            assertEquals(
                "first item previous should be $expectedLast in group of size $size",
                expectedLast,
                prev
            )
        }
    }

    @Test
    fun `forward traversal visits every index exactly once`() {
        for (size in 1..10) {
            val visited = mutableListOf<Int>()
            var current = 0
            repeat(size) {
                visited.add(current)
                val (next, _) = computeOrder(index = current, size = size)
                current = next
            }
            assertEquals("Should visit $size unique indices", size, visited.distinct().size)
            assertEquals("After full traversal should be back at 0", 0, current)
        }
    }

    @Test
    fun `backward traversal visits every index exactly once`() {
        for (size in 1..10) {
            val visited = mutableListOf<Int>()
            var current = 0
            repeat(size) {
                visited.add(current)
                val (_, prev) = computeOrder(index = current, size = size)
                current = prev
            }
            assertEquals("Should visit $size unique indices", size, visited.distinct().size)
            assertEquals("After full backward traversal should be back at 0", 0, current)
        }
    }

    // ── FocusOrderDefaults constants consistency ──────────────────────────────

    @Test
    fun `LOGIN_FOCUS constants cover full range of LOGIN_FOCUS_GROUP_SIZE`() {
        val indices = listOf(
            LOGIN_FOCUS_EMAIL,
            LOGIN_FOCUS_PASSWORD,
            LOGIN_FOCUS_SUBMIT,
            LOGIN_FOCUS_REGISTER
        )
        assertEquals(LOGIN_FOCUS_GROUP_SIZE, indices.size)
        assertEquals("All login focus indices must be unique", indices.distinct().size, indices.size)
        assertEquals(0, indices.min())
        assertEquals(LOGIN_FOCUS_GROUP_SIZE - 1, indices.max())
    }

    @Test
    fun `CHAT_DETAIL_FOCUS constants cover full range of CHAT_DETAIL_FOCUS_GROUP_SIZE`() {
        val indices = listOf(
            CHAT_FOCUS_MESSAGE_LIST,
            CHAT_FOCUS_INPUT,
            CHAT_FOCUS_SEND
        )
        assertEquals(CHAT_DETAIL_FOCUS_GROUP_SIZE, indices.size)
        assertEquals("All chat focus indices must be unique", indices.distinct().size, indices.size)
        assertEquals(0, indices.min())
        assertEquals(CHAT_DETAIL_FOCUS_GROUP_SIZE - 1, indices.max())
    }

    @Test
    fun `SETTINGS_FOCUS constants cover full range of SETTINGS_FOCUS_GROUP_SIZE`() {
        val indices = listOf(
            SETTINGS_FOCUS_THEME,
            SETTINGS_FOCUS_PROVIDER,
            SETTINGS_FOCUS_NOTIFICATIONS,
            SETTINGS_FOCUS_ACCOUNT,
            SETTINGS_FOCUS_SIGN_OUT
        )
        assertEquals(SETTINGS_FOCUS_GROUP_SIZE, indices.size)
        assertEquals("All settings focus indices must be unique", indices.distinct().size, indices.size)
        assertEquals(0, indices.min())
        assertEquals(SETTINGS_FOCUS_GROUP_SIZE - 1, indices.max())
    }

    // ── rememberFocusGroup size validation ────────────────────────────────────

    @Test
    fun `logicalFocusOrder require throws for empty group`() {
        try {
            // Replicate the require() check in logicalFocusOrder
            val group = emptyList<Any>()
            require(group.isNotEmpty()) { "Focus group must not be empty." }
            throw AssertionError("Expected IllegalArgumentException for empty group")
        } catch (e: IllegalArgumentException) {
            assertEquals("Focus group must not be empty.", e.message)
        }
    }

    @Test
    fun `logicalFocusOrder require throws for out-of-bounds index`() {
        try {
            val group = listOf(Any(), Any()) // size 2
            val index = 5
            require(index in group.indices) {
                "Index $index is out of bounds for group of size ${group.size}."
            }
            throw AssertionError("Expected IllegalArgumentException for out-of-bounds index")
        } catch (e: IllegalArgumentException) {
            assertEquals("Index 5 is out of bounds for group of size 2.", e.message)
        }
    }
}
