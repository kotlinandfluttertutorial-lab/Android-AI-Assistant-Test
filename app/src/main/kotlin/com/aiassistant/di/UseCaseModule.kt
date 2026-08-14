/**
 * UseCaseModule.kt — app module
 *
 * Purpose: Hilt module that provides domain use-case instances that cannot declare
 *          their own @Inject constructors because the domain module intentionally has
 *          no javax.inject dependency (to keep it a pure Kotlin module).
 *
 *          Specifically wires [SyncOfflineQueueUseCase] so WorkManager's
 *          [SyncOfflineQueueWorker] (a @HiltWorker) can receive it via Hilt injection.
 *
 * Architecture: app module — DI wiring. Installs into [SingletonComponent].
 *
 * Requirements: 10.2, 10.6
 */
package com.aiassistant.di

import com.aiassistant.domain.repository.MessageRepository
import com.aiassistant.domain.usecase.conversation.SyncOfflineQueueUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    /**
     * Provides [SyncOfflineQueueUseCase] as a singleton so the Hilt-injected
     * [SyncOfflineQueueWorker] can receive it.
     *
     * [MessageRepository] is bound by [com.aiassistant.data.di.ConversationDataModule].
     *
     * @param messageRepository The domain repository providing offline queue sync.
     */
    @Provides
    @Singleton
    fun provideSyncOfflineQueueUseCase(messageRepository: MessageRepository): SyncOfflineQueueUseCase =
        SyncOfflineQueueUseCase(messageRepository)
}
