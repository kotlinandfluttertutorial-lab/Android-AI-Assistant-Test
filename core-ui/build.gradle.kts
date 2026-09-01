plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.aiassistant.core.ui"
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
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
    }
}

// ─── JUnit 5 (Kotest runner) ─────────────────────────────────────────────────
tasks.withType<Test> {
    useJUnitPlatform()
}

// ─── Dependency rule enforcement ─────────────────────────────────────────────
// core-ui MUST NOT depend on feature, data, or domain modules.
configurations.all {
    resolutionStrategy.eachDependency {
        val forbidden = listOf(
            "com.aiassistant.feature",
            "com.aiassistant.data",
            "com.aiassistant.domain"
        )
        if (forbidden.any { requested.group.startsWith(it) }) {
            throw GradleException(
                "core-ui MUST NOT depend on '${requested.group}:${requested.name}'. " +
                    "Dependency direction violation detected."
            )
        }
    }
}

dependencies {
    api(project(":core-common"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Material 3
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowsize)

    // Coil for image loading
    implementation(libs.coil.compose)

    // Markdown rendering
    implementation(libs.compose.markdown)

    // DataStore for theme persistence
    implementation(libs.androidx.datastore.preferences)

    // Activity (provides ComponentActivity used by adaptive layout helpers)
    implementation(libs.androidx.activity.compose)

    // Window size / foldable support
    implementation(libs.androidx.window)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    // Kotest assertions/specs used by core-ui unit tests
    testImplementation(libs.bundles.kotest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
}
