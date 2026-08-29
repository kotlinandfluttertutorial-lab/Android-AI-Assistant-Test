/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : OnDeviceRagEngineModule.kt
 * Purpose    : Hilt bindings for the on-device RAG engine components.
 *              Binds interfaces to their default implementations so the
 *              data module's repositories and the domain's use cases receive
 *              the correct singletons via @Inject.
 *
 * Architecture Layer : Core-AI — DI wiring layer.
 *                      Installed in SingletonComponent so all bindings are
 *                      application-scoped singletons matching the AppDatabase
 *                      lifecycle.
 *
 * Dependencies       : Hilt, core-ai RAG engine classes
 *
 * Design Decision    : OnDeviceEmbeddingModel is bound as a singleton so the
 *                      TFLite interpreter is initialised once and reused across
 *                      every ingestion and query call.  Creating a new interpreter
 *                      per call would cost ~200 ms of warm-up time.
 *                      OnDeviceInferenceEngine is also singleton — model weights
 *                      are large (~2 GB); loading per-request is not feasible.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag.di

import com.aiassistant.core.ai.ondevicerag.MediaPipeInferenceEngine
import com.aiassistant.core.ai.ondevicerag.MiniLmEmbeddingModel
import com.aiassistant.core.ai.ondevicerag.OnDeviceEmbeddingModel
import com.aiassistant.core.ai.ondevicerag.OnDeviceInferenceEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OnDeviceRagEngineModule {

    /**
     * Binds [MiniLmEmbeddingModel] as the [OnDeviceEmbeddingModel] singleton.
     * Replace with a real TFLite-backed implementation when the model file is bundled.
     */
    @Binds
    @Singleton
    abstract fun bindEmbeddingModel(impl: MiniLmEmbeddingModel): OnDeviceEmbeddingModel

    /**
     * Binds [MediaPipeInferenceEngine] as the [OnDeviceInferenceEngine] singleton.
     * Replace or extend when MediaPipe LLM Inference API is integrated.
     */
    @Binds
    @Singleton
    abstract fun bindInferenceEngine(impl: MediaPipeInferenceEngine): OnDeviceInferenceEngine
}
