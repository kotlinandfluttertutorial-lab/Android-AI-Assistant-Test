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

        // Certificate pins are read from the Gradle property `cert_pins` at build time.
        // Pass them as a semicolon-separated list of Base64 SHA-256 SPKI hashes:
        //   ./gradlew assembleRelease -Pcert_pins="hash1;hash2"
        // In CI, set the CERT_PINS environment variable (or Gradle property) before building.
        // An empty value here is intentional for debug builds — pinning is bypassed in debug.
        buildConfigField(
            "String",
            "CERTIFICATE_PINS",
            "\"${project.findProperty("cert_pins") ?: ""}\""
        )

        // Base URL for Retrofit. Override per build variant below.
        // Default here is the emulator localhost so `defaultConfig` alone does not
        // accidentally talk to production.
        buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8000/\"")
    }

    buildTypes {
        debug {
            // Emulator localhost — override with local WiFi IP when testing on a device:
            //   ./gradlew assembleDebug -Pbase_url="http://192.168.x.x:8000/"
            buildConfigField(
                "String",
                "BASE_URL",
                "\"${project.findProperty("base_url") ?: "http://10.0.2.2:8000/"}\""
            )
            // Certificate pinning is bypassed in debug builds (see NetworkModule).
            buildConfigField("String", "CERTIFICATE_PINS", "\"\"")
        }
        release {
            // Production Cloud Run URL. Override via Gradle property or CI env var:
            //   ./gradlew assembleRelease -Pbase_url="https://ai-assistant-backend-106071012091.asia-south1.run.app/"
            buildConfigField(
                "String",
                "BASE_URL",
                "\"${project.findProperty("base_url") ?: "https://ai-assistant-backend-106071012091.asia-south1.run.app/"}\""
            )
            // Production TLS pin(s). Set via:
            //   ./gradlew assembleRelease -Pcert_pins="<sha256-base64-hash>"
            // To get the pin for ai-assistant-backend-106071012091.asia-south1.run.app:
            //   openssl s_client -connect ai-assistant-backend-106071012091.asia-south1.run.app:443 2>/dev/null |
            //     openssl x509 -pubkey -noout |
            //     openssl pkey -pubin -outform DER |
            //     openssl dgst -sha256 -binary | base64
            // IMPORTANT: always include a backup pin in case of certificate rotation.
            buildConfigField(
                "String",
                "CERTIFICATE_PINS",
                "\"${project.findProperty("cert_pins") ?: ""}\""
            )
        }
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
