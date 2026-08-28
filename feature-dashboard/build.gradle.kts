plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aiassistant.feature.dashboard"
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
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Baseline captures pre-existing issues so lint only blocks on NEW violations.
        baseline      = file("lint-baseline.xml")
        abortOnError  = true
        warningsAsErrors = false
        // Produce both text and XML for CI artifact upload
        htmlReport    = true
        xmlReport     = true
        checkDependencies = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Dependency direction guard — feature modules must not import data layer directly
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("com.aiassistant.data")) {
            throw GradleException(
                "feature-dashboard MUST NOT depend on '${requested.name}'. " +
                    "Access data through domain use cases only."
            )
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core-ui"))
    implementation(project(":core-common"))
    implementation(project(":core-network"))

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

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.kotest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
