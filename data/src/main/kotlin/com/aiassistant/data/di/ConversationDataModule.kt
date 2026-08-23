/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ConversationDataModule.kt
 * Purpose    : Hilt module providing ConversationData dependencies to the DI graph
 *
 * Architecture Layer : Data
 * Pattern Used       : Hilt DI Module
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ConversationDataModule.kt
 * Purpose    : Hilt module providing ConversationData dependencies to the DI graph
 *
 * Architecture Layer : Data
 * Pattern Used       : Hilt DI Module
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * ConversationDataModule.kt â€” data module
 *
 * Purpose: Hilt [dagger.Module] that wires all conversation- and message-related bindings
 *          in the data module.
 *
 *          Provides:
 *            - [ConversationApiService] Retrofit implementation
 *            - [MessageApiService] Retrofit implementation
 *          Binds:
 *            - [ConversationRepositoryImpl] â†’ [ConversationRepository]
 *            - [MessageRepositoryImpl]      â†’ [MessageRepository]
 *
 * Architecture: data module â€” installs into [dagger.hilt.components.SingletonComponent]
 *               so all bindings are process-wide singletons. Follows the same pattern as
 *               [AuthDataModule].
 *
 * Design decisions:
 * - Retrofit services are provided via `@Provides` (factory call, not constructor injection).
 * - Repository implementations are bound via `@Binds` (Hilt constructs them directly from
 *   their @Inject constructors â€” no factory needed).
 * - Abstract class + companion object mixes `@Binds` and `@Provides` as required by Hilt.
 *
 * Requirements: 10.1, 10.2, 10.3, 11.1 (fulfilled by ConversationRepositoryImpl and
 *               MessageRepositoryImpl).
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.conversation.ConversationApiService
import com.aiassistant.data.remote.message.MessageApiService
import com.aiassistant.data.repository.ConversationRepositoryImpl
import com.aiassistant.data.repository.MessageRepositoryImpl
import com.aiassistant.domain.repository.ConversationRepository
import com.aiassistant.domain.repository.MessageRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class ConversationDataModule {

    // â”€â”€â”€ @Binds â€” interface â†’ implementation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Binds [ConversationRepositoryImpl] to the [ConversationRepository] domain interface.
     *
     * Any injection site that requests [ConversationRepository] will receive the
     * singleton [ConversationRepositoryImpl] constructed by Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    /**
     * Binds [MessageRepositoryImpl] to the [MessageRepository] domain interface.
     *
     * Any injection site that requests [MessageRepository] will receive the
     * singleton [MessageRepositoryImpl] constructed by Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    // â”€â”€â”€ @Provides â€” factory methods (in companion object) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    companion object {

        /**
         * Creates the [ConversationApiService] Retrofit implementation.
         *
         * Uses the application-scoped [Retrofit] singleton provided by
         * `core-network`'s [com.aiassistant.core.network.di.NetworkModule], ensuring
         * the service shares the same [okhttp3.OkHttpClient] (and therefore the same
         * interceptors) as all other services.
         *
         * @param retrofit The application-level Retrofit singleton.
         * @return A Retrofit-generated implementation of [ConversationApiService].
         */
        @Provides
        @Singleton
        fun provideConversationApiService(retrofit: Retrofit): ConversationApiService =
            retrofit.create(ConversationApiService::class.java)

        /**
         * Creates the [MessageApiService] Retrofit implementation.
         *
         * @param retrofit The application-level Retrofit singleton.
         * @return A Retrofit-generated implementation of [MessageApiService].
         */
        @Provides
        @Singleton
        fun provideMessageApiService(retrofit: Retrofit): MessageApiService =
            retrofit.create(MessageApiService::class.java)
    }
}
