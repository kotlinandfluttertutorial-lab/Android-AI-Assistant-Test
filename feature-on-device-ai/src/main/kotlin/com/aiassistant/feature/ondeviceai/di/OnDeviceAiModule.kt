/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : OnDeviceAiModule.kt
 * Purpose    : Hilt DI module for the feature-on-device-ai module.
 *              Provides HardwareCapabilityDetector, OnDeviceModelManager,
 *              RamMonitor, and the optional OnDeviceInferenceClient.
 *
 * Architecture Layer : Feature (feature-on-device-ai) — DI layer
 * Pattern Used       : Hilt InstallIn(SingletonComponent)
 *
 * Key Concepts:
 *   - OnDeviceInferenceClient is only created when the model file is Ready.
 *     The nullable File? binding means callers that receive null know the model
 *     is not yet available.
 *   - The client is qualified with @OnDeviceModelFile so Hilt can distinguish it
 *     from other File bindings.
 *
 * Requirements: 31.1, 31.2, 31.6
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai.di

import com.aiassistant.core.ai.OnDeviceCapabilityProvider
import com.aiassistant.feature.ondeviceai.OnDeviceCapabilityChecker
import com.aiassistant.feature.ondeviceai.OnDeviceEngine
import com.aiassistant.feature.ondeviceai.OnDeviceInferenceClient
import com.aiassistant.feature.ondeviceai.RamMonitor
import com.aiassistant.feature.ondeviceai.StubOnDeviceEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the optional on-device GGUF model [File].
 * Null means the model has not yet been downloaded/verified.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OnDeviceModelFile

@Module
@InstallIn(SingletonComponent::class)
object OnDeviceAiModule {

    /**
     * Provides the initial (null) model file binding.
     *
     * The real value is set by [com.aiassistant.feature.ondeviceai.OnDeviceAiInitializer]
     * after startup capability detection and checksum verification complete.
     */
    @Provides
    @Singleton
    @OnDeviceModelFile
    fun provideModelFile(): File? = null

    /**
     * Provides the [OnDeviceInferenceClient].
     *
     * The [modelFile] is null at injection time; [OnDeviceAiInitializer] updates
     * [OnDeviceInferenceClient.modelFile] after verifying the local GGUF file,
     * making the client ready for inference without requiring a re-injection.
     */
    @Provides
    @Singleton
    fun provideOnDeviceInferenceClient(
        ramMonitor: RamMonitor,
        @OnDeviceModelFile modelFile: File?
    ): OnDeviceInferenceClient = OnDeviceInferenceClient(
        ramMonitor = ramMonitor,
        modelFile = modelFile
    )
}

/**
 * Separate abstract module needed by Hilt for @Binds declarations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OnDeviceEngineModule {
    /** Binds [StubOnDeviceEngine] as the default [OnDeviceEngine] implementation. */
    @Binds
    @Singleton
    abstract fun bindOnDeviceEngine(stub: StubOnDeviceEngine): OnDeviceEngine

    /**
     * Binds [OnDeviceCapabilityChecker] as the [OnDeviceCapabilityProvider] so that
     * feature-settings can inject the interface without depending on the concrete class
     * (Requirement 19.2 — no feature→feature dependencies).
     */
    @Binds
    @Singleton
    abstract fun bindOnDeviceCapabilityProvider(impl: OnDeviceCapabilityChecker): OnDeviceCapabilityProvider
}
