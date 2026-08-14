/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatModule.kt
 * Purpose    : Hilt module providing Chat dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-chat)
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
 * Module     : feature-chat
 * File       : ChatModule.kt
 * Purpose    : Hilt module providing Chat dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-chat)
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
 * ChatModule.kt â€” feature-chat module
 *
 * Purpose: Hilt [dagger.Module] providing domain use case instances needed by
 *          [ChatViewModel] and [ChatDetailViewModel]. The use cases do not have
 *          `@Inject` constructor annotations in the domain module (pure-Kotlin, no DI
 *          framework dependency by design), so they are provided here via `@Provides`
 *          factory methods.
 *
 * Architecture: feature-chat â€” installs into [dagger.hilt.components.SingletonComponent];
 *               only feature-chat and the app module depend on these bindings.
 * Dependencies: domain (GetConversationsUseCase, CreateConversationUseCase,
 *               DeleteConversationUseCase, SearchConversationsUseCase,
 *               SendMessageUseCase, RegenerateMessageUseCase, ExportConversationUseCase,
 *               ConversationRepository, MessageRepository)
 *
 * Design decisions:
 * - Use `@Provides` instead of `@Binds` because the use cases are instantiated by
 *   factory (not by Hilt constructor injection).
 * - Scoped to [Singleton] so that the single repository instances are shared across
 *   all ViewModel instances over the app lifecycle.
 *
 * Requirements: 11.1, 11.3, 11.5, 2.1, 2.6, 2.7
 */
package com.aiassistant.feature.chat.di

import com.aiassistant.domain.repository.ConversationRepository
import com.aiassistant.domain.repository.MessageRepository
import com.aiassistant.domain.usecase.conversation.CreateConversationUseCase
import com.aiassistant.domain.usecase.conversation.DeleteConversationUseCase
import com.aiassistant.domain.usecase.conversation.ExportConversationUseCase
import com.aiassistant.domain.usecase.conversation.GetConversationsUseCase
import com.aiassistant.domain.usecase.conversation.RegenerateMessageUseCase
import com.aiassistant.domain.usecase.conversation.SearchConversationsUseCase
import com.aiassistant.domain.usecase.conversation.SendMessageUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object ChatModule {

    /**
     * Provides [GetConversationsUseCase] backed by the singleton [ConversationRepository].
     */
    @Provides
    fun provideGetConversationsUseCase(conversationRepository: ConversationRepository): GetConversationsUseCase =
        GetConversationsUseCase(conversationRepository)

    /**
     * Provides [CreateConversationUseCase] backed by the singleton [ConversationRepository].
     */
    @Provides
    fun provideCreateConversationUseCase(conversationRepository: ConversationRepository): CreateConversationUseCase =
        CreateConversationUseCase(conversationRepository)

    /**
     * Provides [DeleteConversationUseCase] backed by the singleton [ConversationRepository].
     */
    @Provides
    fun provideDeleteConversationUseCase(conversationRepository: ConversationRepository): DeleteConversationUseCase =
        DeleteConversationUseCase(conversationRepository)

    /**
     * Provides [SearchConversationsUseCase] backed by the singleton [ConversationRepository].
     */
    @Provides
    fun provideSearchConversationsUseCase(conversationRepository: ConversationRepository): SearchConversationsUseCase =
        SearchConversationsUseCase(conversationRepository)

    /**
     * Provides [SendMessageUseCase] backed by the singleton [MessageRepository].
     */
    @Provides
    fun provideSendMessageUseCase(messageRepository: MessageRepository): SendMessageUseCase =
        SendMessageUseCase(messageRepository)

    /**
     * Provides [RegenerateMessageUseCase] backed by the singleton [MessageRepository].
     */
    @Provides
    fun provideRegenerateMessageUseCase(messageRepository: MessageRepository): RegenerateMessageUseCase =
        RegenerateMessageUseCase(messageRepository)

    /**
     * Provides [ExportConversationUseCase] backed by the singleton [ConversationRepository].
     */
    @Provides
    fun provideExportConversationUseCase(conversationRepository: ConversationRepository): ExportConversationUseCase =
        ExportConversationUseCase(conversationRepository)
}
