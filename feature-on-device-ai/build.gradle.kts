/**
 * feature-on-device-ai/build.gradle.kts
 *
 * Purpose: Gradle build configuration for the on-device AI inference feature module.
 *          Provides NPU/GPU capability detection, GGUF model lifecycle management,
 *          and an AIStreamClient implementation backed entirely by on-device inference.
 * Architecture: feature module → domain/core; never depends on data or other feature modules.
 * Requirements: 31.1–31.8
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aiassistant.feature.ondeviceai"
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
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
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
configurations.all {
    resolutionStrategy.eachDependency {
        val forbidden = listOf(
            "com.aiassistant.data"
        )
        if (forbidden.any { requested.group.startsWith(it) }) {
            throw GradleException(
                "feature-on-device-ai MUST NOT depend on '${requested.group}:${requested.name}'. " +
                    "Dependency direction violation detected."
            )
        }
    }
}

dependencies {
    // Core modules
    implementation(project(":core-ai"))
    implementation(project(":core-common"))

    // Android core
    implementation(libs.androidx.core.ktx)

    // Serialization (model manifest JSON)
    implementation(libs.kotlinx.serialization.json)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.turbine)
    // OkHttp is used in property tests to verify zero outbound HTTP calls are made
    testImplementation(libs.okhttp.core)
}
