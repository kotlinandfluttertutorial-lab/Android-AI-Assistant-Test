plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aiassistant.feature.auth"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
    }
}

// ─── Dependency rule enforcement ─────────────────────────────────────────────
// feature-auth MUST NOT depend on data or other feature modules directly.
configurations.all {
    resolutionStrategy.eachDependency {
        val forbidden = listOf(
            "com.aiassistant.data"
        )
        if (forbidden.any { requested.group.startsWith(it) }) {
            throw GradleException(
                "feature-auth MUST NOT depend on '${requested.group}:${requested.name}'. " +
                    "Dependency direction violation detected."
            )
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core-ui"))
    implementation(project(":core-common"))
    implementation(project(":core-security"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Lifecycle / ViewModel
    implementation(libs.bundles.lifecycle)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)

    // Google Sign-In
    implementation(libs.google.auth)
    // Credential Manager — Sign in with Google (replaces deprecated GoogleSignInClient)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.kotest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.compose.ui.tooling)
}
