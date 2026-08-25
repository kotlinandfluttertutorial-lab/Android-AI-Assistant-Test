# Skill: Android Module Scaffold

## Purpose
Scaffold a new Gradle module for the Android AI Assistant project that follows the
established multi-module Clean Architecture conventions: correct `build.gradle.kts`,
package structure, Hilt entry point, and navigation wiring.

## When to Use
- Adding a new `:feature-*` module (e.g. `feature-translator`, `feature-persona`)
- Adding a new `:core-*` module (e.g. `core-analytics`, `core-push`)
- Splitting an existing module that has grown too large

---

## Project Conventions

### Package root
All modules use the root package `com.aiassistant`. Feature modules append their own
segment, e.g. `com.aiassistant.feature.translator`.

### Module types
| Type | Namespace | Android plugin | Typical dependencies |
|------|-----------|----------------|----------------------|
| `:app` | `com.aiassistant` | `com.android.application` | all feature + core modules |
| `:feature-*` | `com.aiassistant.feature.<name>` | `com.android.library` | `:domain`, `:core-common`, `:core-ui`, `:core-ai` (if AI) |
| `:core-*` | `com.aiassistant.core.<name>` | `com.android.library` | `:core-common` only (no feature deps) |
| `:domain` | `com.aiassistant.domain` | `org.jetbrains.kotlin.jvm` | nothing (pure Kotlin) |
| `:data` | `com.aiassistant.data` | `com.android.library` | `:domain`, `:core-network`, `:core-database` |

### Forbidden dependency edges (enforced by `check-module-deps.sh`)
- `feature-*` → `feature-*` (cross-feature imports are forbidden)
- `domain` → `data` or any feature
- `core-*` → `feature-*`

---

## Step-by-Step Instructions

### 1. Create the directory tree

For a feature module named `feature-<name>`:

```
feature-<name>/
  build.gradle.kts
  src/
    main/
      AndroidManifest.xml
      kotlin/
        com/aiassistant/feature/<name>/
          di/
            <Name>Module.kt
          navigation/
            <Name>NavGraph.kt
          ui/
            <Name>Screen.kt
            <Name>ViewModel.kt
            <Name>UiState.kt
    test/
      kotlin/
        com/aiassistant/feature/<name>/
          <Name>ViewModelTest.kt
    androidTest/
      kotlin/
        com/aiassistant/feature/<name>/
          <Name>ScreenTest.kt
```

### 2. Write `build.gradle.kts`

Use this template (replace `<name>` with the module name):

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aiassistant.feature.<name>"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "com.aiassistant.HiltTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Architecture layers
    implementation(project(":domain"))
    implementation(project(":core-common"))
    implementation(project(":core-ui"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.navigation.compose)

    // Lifecycle / ViewModel
    implementation(libs.bundles.lifecycle)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.arch.core.testing)

    // Instrumented tests
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

> Add `implementation(project(":core-ai"))` only if the feature talks directly to
> `AIStreamClient` or `LlmProvider`. Most features only consume domain use cases.

### 3. Write `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

Library modules need no `<application>` block.

### 4. Write the Hilt module

```kotlin
// di/<Name>Module.kt
package com.aiassistant.feature.<name>.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object <Name>Module
// Add @Provides / @Binds here as the feature grows.
```

### 5. Write the navigation graph extension

Follow the pattern used by every other feature module. The function is called from
`MainActivity.rootNavHost()`:

```kotlin
// navigation/<Name>NavGraph.kt
package com.aiassistant.feature.<name>

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

// Canonical route constants — referenced by MainActivity deep-link wiring
object <Name>Route {
    const val GRAPH = "<name>"
    const val MAIN  = "<name>/main"
}

fun NavGraphBuilder.<name>NavGraph(
    navController: NavController,
    onNavigateBack: () -> Unit = { navController.popBackStack() }
) {
    composable(route = <Name>Route.MAIN) {
        <Name>Screen(onNavigateBack = onNavigateBack)
    }
}
```

Then register it in `app/src/main/kotlin/com/aiassistant/MainActivity.kt`:

```kotlin
<name>NavGraph(
    navController = navController,
    onNavigateBack = { navController.popBackStack() }
)
```

And wire a deep link if needed (see existing deep-link blocks for `chat`, `voice`, `rag`).

### 6. Register the module in `settings.gradle.kts`

```kotlin
include(":feature-<name>")
```

### 7. Add to `:app` dependencies

In `app/build.gradle.kts` inside `dependencies { }`:

```kotlin
implementation(project(":feature-<name>"))
```

---

## Checklist

- [ ] Directory tree created
- [ ] `build.gradle.kts` written with correct `namespace`
- [ ] `AndroidManifest.xml` present (library manifest, no `<application>`)
- [ ] Hilt `@Module` created and installed in the correct component
- [ ] Navigation graph extension function written
- [ ] Route constants defined in a companion/object
- [ ] Module registered in `settings.gradle.kts`
- [ ] Module added to `:app` dependencies
- [ ] No forbidden dependency edges introduced
- [ ] At least one `ViewModelTest` stub created under `src/test/`
- [ ] Gradle sync passes without errors

---

## Version Reference

All versions come from `gradle/libs.versions.toml`. Never hard-code version strings.

| Key | Current version |
|-----|----------------|
| `kotlin` | 2.0.21 |
| `agp` | 8.8.0 |
| `hilt` | 2.52 |
| `composeBom` | 2024.09.03 |
| `androidxNavigation` | 2.8.2 |
| `coroutines` | 1.9.0 |
| `compileSdk` | 35 |
| `minSdk` | 26 |
