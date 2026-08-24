/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-translator
 * File       : TranslatorModule.kt
 * Purpose    : Hilt module providing Translator dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-translator)
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
 * Module     : feature-translator
 * File       : TranslatorModule.kt
 * Purpose    : Hilt module providing Translator dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-translator)
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
 * TranslatorModule.kt
 *
 * Purpose: Hilt [dagger.Module] providing domain use case instances and persistence
 *          helpers needed by [TranslatorViewModel]. The use cases do not carry @Inject
 *          annotations in the domain module (pure-Kotlin, no DI framework dependency
 *          by design), so they are provided here via @Provides factory methods.
 * Architecture: feature-translator â€” installs into [dagger.hilt.components.SingletonComponent];
 *               only feature-translator and the app module depend on these bindings.
 * Dependencies: domain (TranslateTextUseCase, TranslationRepository),
 *               TranslatorPreferences (DataStore language pair persistence)
 *
 * Requirements: 10.5, 19.1
 *
 * Design decisions:
 * - Scoped to Singleton so the single TranslationRepository instance is shared across all
 *   ViewModel instances over the app lifecycle.
 * - Feature module MUST NOT depend on :data â€” TranslationRepository is resolved from the
 *   data module's binding registered at the app/data level, not imported here directly.
 * - TranslatorPreferences uses @ApplicationContext to access the DataStore delegate,
 *   matching the pattern of ThemePreferences in core-ui.
 */
package com.aiassistant.feature.translator.di

import android.content.Context
import com.aiassistant.domain.repository.TranslationRepository
import com.aiassistant.domain.usecase.translator.TranslateTextUseCase
import com.aiassistant.feature.translator.TranslatorPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the feature-translator module.
 *
 * Provides [TranslateTextUseCase] using the application-level [TranslationRepository]
 * singleton that is bound by the data module's Hilt component, and [TranslatorPreferences]
 * for language pair persistence.
 */
@Module
@InstallIn(SingletonComponent::class)
object TranslatorModule {

    /**
     * Provides [TranslateTextUseCase] for use in [TranslatorViewModel].
     *
     * @param translationRepository Application-level translation repository singleton.
     * @return A singleton [TranslateTextUseCase] instance.
     */
    @Provides
    @Singleton
    fun provideTranslateTextUseCase(translationRepository: TranslationRepository): TranslateTextUseCase =
        TranslateTextUseCase(translationRepository)

    /**
     * Provides [TranslatorPreferences] for language pair persistence in [TranslatorViewModel].
     *
     * @param context Application context used to access the DataStore instance.
     * @return A singleton [TranslatorPreferences] instance.
     */
    @Provides
    @Singleton
    fun provideTranslatorPreferences(@ApplicationContext context: Context): TranslatorPreferences =
        TranslatorPreferences(context)
}
