plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aiassistant.core.common"
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
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
}

// ─── Dependency rule enforcement ─────────────────────────────────────────────
// core-common MUST NOT depend on any feature, data, or domain module.
configurations.all {
    resolutionStrategy.eachDependency {
        val forbidden = listOf(
            "com.aiassistant.feature",
            "com.aiassistant.data",
            "com.aiassistant.domain"
        )
        if (forbidden.any { requested.group.startsWith(it) }) {
            throw GradleException(
                "core-common MUST NOT depend on '${requested.group}:${requested.name}'. " +
                    "Dependency direction violation detected."
            )
        }
    }
}

// ─── JUnit 5 (Kotest runner) ──────────────────────────────────────────────────
tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "3g"
    forkEvery = 1
}

dependencies {
    // core-common: no dependencies on other project modules — this is the base
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.kotest)
}
