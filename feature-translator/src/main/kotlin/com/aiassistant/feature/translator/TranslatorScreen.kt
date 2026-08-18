/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-translator
 * File       : TranslatorScreen.kt
 * Purpose    : Compose UI screen for the Translator feature
 *
 * Architecture Layer : Feature (feature-translator)
 * Pattern Used       : Jetpack Compose Screen
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
 * File       : TranslatorScreen.kt
 * Purpose    : Compose UI screen for the Translator feature
 *
 * Architecture Layer : Feature (feature-translator)
 * Pattern Used       : Jetpack Compose Screen
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
 * TranslatorScreen.kt
 *
 * Purpose: Jetpack Compose screen for the Translator feature. Renders language pair
 *          selector, text input, translation result, speech input FAB, and offline/error
 *          banners. Routes translation requests through TranslatorViewModel.
 * Architecture: feature-translator â€” UI layer; observes TranslatorViewModel state.
 * Dependencies: TranslatorViewModel (Hilt), core-ui (ErrorBanner, OfflineBanner, AppTheme,
 *               spacing tokens), Compose Material 3, SpeechRecognizer (via activity result)
 *
 * Requirements: 10.5, 19.1, 23.1
 *
 * Design decisions:
 * - SpeechRecognizer is invoked via rememberLauncherForActivityResult with
 *   RecognizerIntent.ACTION_RECOGNIZE_SPEECH â€” no Context stored in ViewModel.
 * - Language pair selector uses DropdownMenu composables with a swap IconButton between
 *   the two pickers.
 * - Translate button is disabled when the input text is blank or state is Translating.
 * - All interactive elements carry contentDescription for TalkBack (Requirement 23.1).
 * - Offline banner is shown whenever isOffline=true regardless of translation state.
 * - collectAsStateWithLifecycle() avoids collecting in the background.
 */
package com.aiassistant.feature.translator

import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.OfflineBanner
import com.aiassistant.core.ui.spacing

/**
 * Entry-point composable for the Translator screen.
 *
 * @param onNavigateBack Called when the user presses the back arrow in the top bar.
 * @param viewModel      Hilt-injected [TranslatorViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(onNavigateBack: () -> Unit = {}, viewModel: TranslatorViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
    val selectedPair by viewModel.selectedLanguagePair.collectAsStateWithLifecycle()

    // Local text field state â€” persisted across recompositions.
    var inputText by rememberSaveable { mutableStateOf("") }

    // â”€â”€â”€ Speech recogniser launcher â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!transcript.isNullOrBlank()) {
            inputText = transcript
            viewModel.onSpeechResult(transcript)
        } else {
            viewModel.onSpeechError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Translator") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.startListening()
                    val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak nowâ€¦")
                    }
                    speechLauncher.launch(intent)
                },
                modifier = Modifier.semantics {
                    contentDescription = "Start speech input"
                },
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // â”€â”€â”€ Offline banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isOffline) {
                OfflineBanner(
                    message = "You're offline. Translation requires an internet connection.",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.md)
            ) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

                // â”€â”€â”€ Language pair selector â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                LanguagePairSelector(
                    selectedPair = selectedPair,
                    onSelectSource = { code, name ->
                        viewModel.selectLanguagePair(
                            selectedPair.copy(sourceCode = code, sourceName = name)
                        )
                    },
                    onSelectTarget = { code, name ->
                        viewModel.selectLanguagePair(
                            selectedPair.copy(targetCode = code, targetName = name)
                        )
                    },
                    onSwap = { viewModel.swapLanguages() }
                )

                Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

                // â”€â”€â”€ Text input â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Enter text to translate" },
                    label = { Text("Text to translate") },
                    placeholder = { Text("Type hereâ€¦") },
                    minLines = 4,
                    maxLines = 8,
                    enabled = uiState !is TranslatorUiState.Translating
                )

                Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

                // â”€â”€â”€ Translate button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                Button(
                    onClick = { viewModel.translate(inputText) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Translate text" },
                    enabled = inputText.isNotBlank() && uiState !is TranslatorUiState.Translating
                ) {
                    Text(text = "Translate")
                }

                Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

                // â”€â”€â”€ State-specific content â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                when (val state = uiState) {
                    is TranslatorUiState.Idle -> Unit

                    is TranslatorUiState.Listening -> ListeningIndicator()

                    is TranslatorUiState.Translating -> TranslatingIndicator()

                    is TranslatorUiState.Success -> TranslationResultCard(
                        state = state,
                        onClear = {
                            inputText = ""
                            viewModel.reset()
                        }
                    )

                    is TranslatorUiState.Error -> ErrorBanner(
                        message = state.message,
                        onRetry = if (inputText.isNotBlank()) {
                            { viewModel.translate(inputText) }
                        } else {
                            null
                        },
                        contentDescription = if (state.isOffline) {
                            "Offline error: ${state.message}"
                        } else {
                            "Translation error: ${state.message}"
                        }
                    )
                }

                // Bottom spacer so FAB doesn't overlap last card.
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xxl))
            }
        }
    }
}

// â”€â”€â”€ Language pair selector â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun LanguagePairSelector(
    selectedPair: LanguagePair,
    onSelectSource: (code: String, name: String) -> Unit,
    onSelectTarget: (code: String, name: String) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Language pair selector" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Source language picker
        LanguageDropdown(
            label = "From",
            selectedCode = selectedPair.sourceCode,
            selectedName = selectedPair.sourceName,
            onLanguageSelected = onSelectSource,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Source language: ${selectedPair.sourceName}" }
        )

        // Swap button
        IconButton(
            onClick = onSwap,
            modifier = Modifier
                .padding(horizontal = MaterialTheme.spacing.xs)
                .semantics { contentDescription = "Swap source and target languages" }
        ) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // Target language picker
        LanguageDropdown(
            label = "To",
            selectedCode = selectedPair.targetCode,
            selectedName = selectedPair.targetName,
            onLanguageSelected = onSelectTarget,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Target language: ${selectedPair.targetName}" }
        )
    }
}

@Composable
private fun LanguageDropdown(
    label: String,
    selectedCode: String,
    selectedName: String,
    onLanguageSelected: (code: String, name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$label language: $selectedName. Tap to change." }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = selectedName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SupportedLanguages.all.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = if (code == selectedCode) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                    onClick = {
                        onLanguageSelected(code, name)
                        expanded = false
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Select $name"
                    }
                )
            }
        }
    }
}

// â”€â”€â”€ State-specific sub-composables â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun ListeningIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.md)
            .semantics { contentDescription = "Listening for speech" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
        Text(
            text = "Listeningâ€¦",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TranslatingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.md)
            .semantics { contentDescription = "Translating" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
        Text(
            text = "Translatingâ€¦",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TranslationResultCard(
    state: TranslatorUiState.Success,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sourceLangName = SupportedLanguages.displayNameFor(state.sourceLang)
    val targetLangName = SupportedLanguages.displayNameFor(state.targetLang)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Translation result: ${state.translatedText}"
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md)
        ) {
            // Header row â€” target language name + offline badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$sourceLangName â†’ $targetLangName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.isOffline) {
                    Text(
                        text = "Offline result",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.semantics {
                            contentDescription = "This translation was produced by the offline model"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            // Translated text
            Text(
                text = state.translatedText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            // Clear button
            TextButton(
                onClick = onClear,
                modifier = Modifier.semantics { contentDescription = "Clear translation result" }
            ) {
                Text(
                    text = "Clear",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "TranslatorScreen â€“ Idle")
@Composable
private fun TranslatorIdlePreview() {
    AppTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            LanguagePairSelector(
                selectedPair = SupportedLanguages.defaultPair,
                onSelectSource = { _, _ -> },
                onSelectTarget = { _, _ -> },
                onSwap = {}
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = "",
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Text to translate") },
                minLines = 4
            )
        }
    }
}

@Preview(showBackground = true, name = "TranslatorScreen â€“ Success")
@Composable
private fun TranslatorSuccessPreview() {
    AppTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            TranslationResultCard(
                state = TranslatorUiState.Success(
                    sourceText = "Hello, how are you?",
                    translatedText = "Hola, Â¿cÃ³mo estÃ¡s?",
                    sourceLang = "en",
                    targetLang = "es",
                    isOffline = false
                ),
                onClear = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "TranslatorScreen â€“ Offline Success")
@Composable
private fun TranslatorOfflineSuccessPreview() {
    AppTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            OfflineBanner()
            Spacer(modifier = Modifier.height(8.dp))
            TranslationResultCard(
                state = TranslatorUiState.Success(
                    sourceText = "Good morning",
                    translatedText = "Bonjour",
                    sourceLang = "en",
                    targetLang = "fr",
                    isOffline = true
                ),
                onClear = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "TranslatorScreen â€“ Error")
@Composable
private fun TranslatorErrorPreview() {
    AppTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            ErrorBanner(
                message = "Translation failed. Please check your connection and try again.",
                onRetry = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "TranslatorScreen â€“ Translating")
@Composable
private fun TranslatorTranslatingPreview() {
    AppTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            TranslatingIndicator()
        }
    }
}
