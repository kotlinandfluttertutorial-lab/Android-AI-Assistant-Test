/*
 * feature-on-device-rag/build.gradle.kts
 *
 * Purpose: Gradle build configuration for the on-device RAG feature module.
 *          Provides document ingestion, RAG chat, benchmark, and model management
 *          screens backed entirely by on-device inference — no Backend dependency.
 *
 * Architecture: feature module → domain + core-*; NEVER depends on data or other features.
 *
 * Requirements: 19.1, 19.2, 30.2, 33.1–33.10, 35.1–35.9, 36.1–36.8, 37.1–37.10
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aiassistant.feature.ondevicerag"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        missingDimensionStrategy("environment", "cloud", "local")
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
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// ─── JUnit 5 (Kotest runner) ──────────────────────────────────────────────────
tasks.withType<Test> {
    useJUnitPlatform()
}

// ─── Dependency rule enforcement ─────────────────────────────────────────────
// feature-on-device-rag MUST NOT depend on the data module or any other feature module.
configurations.all {
    resolutionStrategy.eachDependency {
        val forbidden = listOf(
            "com.aiassistant.data"
        )
        if (forbidden.any { requested.group.startsWith(it) }) {
            throw GradleException(
                "feature-on-device-rag MUST NOT depend on '${requested.group}:${requested.name}'. " +
                    "Dependency direction violation detected."
            )
        }
    }
}

dependencies {
    // Architecture layers — domain provides repository interfaces + use cases;
    // core-ai provides RAG engine components (Chunker, QueryRouter, LocalVectorIndex, engines).
    // Data is accessed exclusively through domain repository interfaces (Hilt resolves at app level).
    implementation(project(":domain"))
    implementation(project(":core-ui"))
    implementation(project(":core-common"))
    implementation(project(":core-ai")) // QueryRouter, LocalVectorIndex, engine interfaces
    implementation(project(":core-database")) // OnDeviceChunkDao (via LocalVectorIndex only)

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

    // Coroutines + Flow
    implementation(libs.bundles.coroutines)

    // Serialization (for model manifest JSON)
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.turbine)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
