plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aiassistant.core.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "CERTIFICATE_PINS", "\"\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

// ─── Dependency rule enforcement ─────────────────────────────────────────────
// core-network MUST NOT depend on feature, data, or domain modules.
configurations.all {
    resolutionStrategy.eachDependency {
        val forbidden = listOf(
            "com.aiassistant.feature",
            "com.aiassistant.data",
            "com.aiassistant.domain"
        )
        if (forbidden.any { requested.group.startsWith(it) }) {
            throw GradleException(
                "core-network MUST NOT depend on '${requested.group}:${requested.name}'. " +
                    "Dependency direction violation detected."
            )
        }
    }
}

dependencies {
    api(project(":core-common"))
    api(project(":core-security"))

    // Domain module for federation entities and repository interfaces
    implementation(project(":domain"))

    // Retrofit + OkHttp
    implementation(libs.bundles.retrofit)
    implementation(libs.kotlinx.serialization.json)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Hilt WorkManager integration
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Coroutines (including play-services for Firebase Tasks → suspend)
    implementation(libs.bundles.coroutines)
    implementation(libs.kotlinx.coroutines.play.services)

    // Firebase Remote Config (for FederationConfigRepository)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config)

    // Timber (logging)
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.turbine)
}
