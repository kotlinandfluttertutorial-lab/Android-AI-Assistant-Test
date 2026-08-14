/**
 * TestDispatcherProvider.kt — data module test utilities
 *
 * Purpose: Shared [DispatcherProvider] test double for all data module unit tests.
 *          Returns [UnconfinedTestDispatcher] for every dispatcher so coroutines
 *          run synchronously and deterministically in tests.
 *
 * Architecture: test utility — internal to the data module test source set.
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * [DispatcherProvider] test double that returns [UnconfinedTestDispatcher] for every
 * dispatcher so coroutines run synchronously and deterministically in tests.
 */
internal class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()) :
    DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
    override val mainImmediate: CoroutineDispatcher = dispatcher
    override val unconfined: CoroutineDispatcher = dispatcher
}
