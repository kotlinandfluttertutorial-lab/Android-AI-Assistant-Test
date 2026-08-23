package com.aiassistant.feature.history.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * HistoryModule.kt — feature-history module
 *
 * All conversation use cases needed by [HistoryViewModel]
 * (GetConversationsUseCase, SearchConversationsUseCase, DeleteConversationUseCase,
 * ExportConversationUseCase) are provided by feature-chat's ChatModule at
 * ViewModelComponent scope, which is shared across the whole app.
 * No duplicate bindings are needed here.
 *
 * Requirements: 11.1, 11.2, 11.6
 */
@Module
@InstallIn(ViewModelComponent::class)
object HistoryModule
