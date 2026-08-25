# Skill: Feature Screen Scaffold

## Purpose
Create a complete, production-ready Compose screen for any feature module in the
Android AI Assistant project. Covers the full MVVM slice: `UiState`, `ViewModel`,
`Screen` composable, and navigation wiring — all matching the patterns already in use
across `feature-chat`, `feature-voice`, `feature-rag`, and the other 15 feature modules.

## When to Use
- Adding a new top-level screen inside an existing feature module
- Adding a detail/sub-screen (e.g. `ChatDetailScreen` inside `feature-chat`)
- Refactoring a screen to match the project's current state-hoisting conventions

---

## Architecture Layers Involved

```
NavGraph (composable route)
    └── <Name>Screen          ← pure Compose, zero business logic
            └── <Name>ViewModel  ← @HiltViewModel, exposes StateFlow<UiState>
                    └── Use Cases  ← from :domain, injected by Hilt
                            └── Repository interfaces  ← from :domain
```

No `Screen` composable may import from `:data` or `:core-network`. It may only import
from `:core-ui`, `:core-common`, `:domain` (for model types), and its own feature module.

---

## File Templates

### 1. `<Name>UiState.kt`

```kotlin
package com.aiassistant.feature.<name>

import com.aiassistant.core.common.DomainError

/**
 * Immutable snapshot of everything the <Name>Screen needs to render.
 *
 * Design rule: every field has a sensible default so the ViewModel can be
 * constructed and immediately observed without a loading race condition.
 */
data class <Name>UiState(
    val isLoading: Boolean = false,
    val error: DomainError? = null,
    // TODO: add domain-specific fields here
)
```

`DomainError` lives in `:core-common` and is the project-wide sealed error type.
Never use raw `Exception` or `String` for error state.

---

### 2. `<Name>ViewModel.kt`

```kotlin
package com.aiassistant.feature.<name>

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
// import your domain use cases here
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for <Name>Screen.
 *
 * - Exposes a single [StateFlow<[<Name>UiState]>] consumed by the Screen.
 * - All coroutines are launched with [dispatchers.io] for data work;
 *   [StateFlow] is thread-safe so UI updates need no explicit main-thread switch.
 * - Use [viewModelScope] — never launch GlobalScope.
 */
@HiltViewModel
class <Name>ViewModel @Inject constructor(
    private val dispatchers: DispatcherProvider,
    // inject domain use cases here
) : ViewModel() {

    private val _uiState = MutableStateFlow(<Name>UiState())
    val uiState: StateFlow<<Name>UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Example: call a use case
            // when (val result = someUseCase()) {
            //     is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, data = result.data) }
            //     is ApiResult.Error   -> _uiState.update { it.copy(isLoading = false, error = result.error) }
            //     is ApiResult.NetworkUnavailable -> _uiState.update { it.copy(isLoading = false, error = DomainError.NetworkUnavailable) }
            // }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
```

#### ViewModel rules
- Always use `_uiState.update { }` (not `_uiState.value = `). `update` is atomic.
- `SavedStateHandle` is required for any ViewModel that receives navigation arguments
  (see `ChatDetailViewModel` which reads `conversationId` this way).
- Expose **events** (one-shot navigation, snackbars) via `SharedFlow`, not `StateFlow`.

---

### 3. `<Name>Screen.kt`

```kotlin
package com.aiassistant.feature.<name>

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * <Name>Screen — pure presentation layer.
 *
 * State is hoisted: the Screen only receives lambdas and reads [uiState].
 * It never calls ViewModel functions directly from nested composables;
 * instead it passes lambdas down, keeping sub-composables side-effect free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <Name>Screen(
    onNavigateBack: () -> Unit,
    viewModel: <Name>ViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    <Name>ScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onRetry = viewModel::load,
        onDismissError = viewModel::clearError,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <Name>ScreenContent(
    uiState: <Name>UiState,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("<Name>") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator()
                uiState.error != null -> ErrorContent(
                    error = uiState.error,
                    onRetry = onRetry,
                    onDismiss = onDismissError
                )
                else -> {
                    // TODO: render main content
                }
            }
        }
    }
}

// Reuse core-ui error composable when available; inline stub shown here.
@Composable
private fun ErrorContent(
    error: com.aiassistant.core.common.DomainError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = error.toString(), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(androidx.compose.ui.unit.dp.let { 8.dp }))
        Button(onClick = onRetry) { Text("Retry") }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
```

#### Screen rules
- **Always** use `collectAsStateWithLifecycle()` (from `lifecycle-runtime-compose`), not
  `collectAsState()`. The former respects the Activity lifecycle and stops collection
  when the app is backgrounded, preventing unnecessary work.
- The public `@Composable fun <Name>Screen(...)` receives the ViewModel via `hiltViewModel()`.
  The private `<Name>ScreenContent(...)` receives only plain data and lambdas — this
  split makes the content composable fully previewable without a ViewModel.
- Do not call `remember { mutableStateOf(...) }` for state that must survive
  process death — use `rememberSaveable` or keep it in the ViewModel.

---

### 4. Preview

Add at the bottom of `<Name>Screen.kt`:

```kotlin
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun <Name>ScreenPreview() {
    com.aiassistant.core.ui.AppTheme {
        <Name>ScreenContent(
            uiState = <Name>UiState(),
            onNavigateBack = {},
            onRetry = {},
            onDismissError = {},
        )
    }
}
```

---

## Navigation Arguments

When a screen requires arguments (e.g. `conversationId`), define them in the route:

```kotlin
// In <Name>NavGraph.kt
const val ARG_ID = "itemId"
const val DETAIL_ROUTE = "<name>/detail/{$ARG_ID}"

composable(
    route = DETAIL_ROUTE,
    arguments = listOf(navArgument(ARG_ID) { type = NavType.StringType })
) {
    <Name>DetailScreen(onNavigateBack = { navController.popBackStack() })
}
```

Read in the ViewModel via `SavedStateHandle`:

```kotlin
@HiltViewModel
class <Name>DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    val itemId: String = checkNotNull(savedStateHandle[ARG_ID]) {
        "<Name>DetailViewModel requires a non-null $ARG_ID argument"
    }
}
```

---

## Checklist

- [ ] `<Name>UiState` is a `data class` with all fields defaulted
- [ ] `DomainError?` is used for error state (not `String`)
- [ ] ViewModel uses `_uiState.update { }` exclusively
- [ ] All coroutines use `viewModelScope.launch(dispatchers.io)`
- [ ] Screen uses `collectAsStateWithLifecycle()`
- [ ] Public Screen and private ScreenContent are split for preview support
- [ ] `AppTheme` wraps the preview
- [ ] Navigation route constants defined in a named object
- [ ] No `:data` / `:core-network` imports inside the feature's Compose files
- [ ] No forbidden cross-feature imports
