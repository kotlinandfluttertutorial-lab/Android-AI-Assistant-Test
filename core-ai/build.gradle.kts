plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aiassistant.core.ai"
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
}

// ─── JUnit 5 (Kotest runner) ──────────────────────────────────────────────────
tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "3g"
    forkEvery = 1
}

// ─── Dependency rule enforcement ─────────────────────────────────────────────
// core-ai MUST NOT depend on feature, data, or domain modules.
configurations.all {
    resolutionStrategy.eachDependency {
        val forbidden = listOf(
            "com.aiassistant.feature",
            "com.aiassistant.data",
            "com.aiassistant.domain"
        )
        if (forbidden.any { requested.group.startsWith(it) }) {
            throw GradleException(
                "core-ai MUST NOT depend on '${requested.group}:${requested.name}'. " +
                    "Dependency direction violation detected."
            )
        }
    }
}

dependencies {
    api(project(":core-common"))

    // OkHttp WebSocket client
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    // JSON parsing
    implementation(libs.kotlinx.serialization.json)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Coroutines + Flow
    implementation(libs.bundles.coroutines)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.bundles.kotest)
}
