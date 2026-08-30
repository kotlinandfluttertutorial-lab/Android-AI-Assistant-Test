/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : OnDeviceRagEngineModule.kt
 * Purpose    : Hilt bindings for the on-device RAG engine components.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag.di

import com.aiassistant.core.ai.ondevicerag.LocalVectorIndexImpl
import com.aiassistant.core.ai.ondevicerag.MediaPipeInferenceEngine
import com.aiassistant.core.ai.ondevicerag.MiniLmEmbeddingModel
import com.aiassistant.core.ai.ondevicerag.QueryRouterImpl
import com.aiassistant.core.common.LocalVectorIndex
import com.aiassistant.core.common.OnDeviceEmbeddingModel
import com.aiassistant.core.common.OnDeviceInferenceEngine
import com.aiassistant.core.common.QueryRouter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OnDeviceRagEngineModule {

    @Binds
    @Singleton
    abstract fun bindEmbeddingModel(impl: MiniLmEmbeddingModel): OnDeviceEmbeddingModel

    @Binds
    @Singleton
    abstract fun bindInferenceEngine(impl: MediaPipeInferenceEngine): OnDeviceInferenceEngine

    @Binds
    @Singleton
    abstract fun bindVectorIndex(impl: LocalVectorIndexImpl): LocalVectorIndex

    @Binds
    @Singleton
    abstract fun bindQueryRouter(impl: QueryRouterImpl): QueryRouter
}
