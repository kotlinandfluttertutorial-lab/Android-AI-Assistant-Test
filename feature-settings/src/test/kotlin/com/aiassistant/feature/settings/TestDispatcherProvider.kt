/*
 * ─────────────────────────────────────────────────────────────────────────────
 * feature-settings — shared test helper
 * TestDispatcherProvider.kt
 *
 * Shared test-only DispatcherProvider implementation used by all test suites
 * in the feature-settings module.  Extracted here to avoid duplicate class
 * declarations across test files that would cause a compilation error.
 * ─────────────────────────────────────────────────────────────────────────────
 */
package com.aiassistant.feature.settings

import com.aiassistant.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
internal class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
    override val mainImmediate: CoroutineDispatcher = dispatcher
    override val unconfined: CoroutineDispatcher = dispatcher
}
