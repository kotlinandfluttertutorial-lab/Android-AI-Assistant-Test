plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    jacoco
}

android {
    namespace = "com.aiassistant.data"
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

// ─── Dependency rule enforcement ─────────────────────────────────────────────
// data MUST NOT depend on any feature module.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("com.aiassistant.feature")) {
            throw GradleException(
                "data MUST NOT depend on '${requested.group}:${requested.name}'. " +
                    "Dependency direction violation detected."
            )
        }
    }
}

// ─── JaCoCo coverage enforcement ─────────────────────────────────────────────
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.withType<Test> {
    finalizedBy(tasks.withType<JacocoReport>())
    useJUnitPlatform()
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.withType<Test>())
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(files("src/main/kotlin"))
    val excludePatterns = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*_HiltModules*",
        "**/*_Factory*",
        "**/*_MembersInjector*",
        "**/*Hilt_*",
        "**/hilt/**",
        // Hilt DI module classes — not unit-testable (framework wiring)
        "**/di/**",
        // WorkManager worker — requires Android runtime
        "**/sync/**",
        // Room-generated local data source implementations
        "**/local/**",
        // Remote API service interfaces (Retrofit stubs, no business logic)
        "**/remote/user/**",
        "**/remote/translator/**",
        "**/remote/resume/**",
        "**/remote/productivity/**",
        "**/remote/conversation/**",
        "**/remote/memory/**"
    )
    classDirectories.setFrom(
        fileTree("build/tmp/kotlin-classes/debug") {
            exclude(excludePatterns)
        } + fileTree("build/intermediates/asm_instrumented_project_classes/debug") {
            exclude(excludePatterns)
        }
    )
    executionData.setFrom(fileTree(layout.buildDirectory) { include("jacoco/*.exec") })
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    violationRules {
        rule {
            limit {
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

dependencies {
    // Architecture modules
    implementation(project(":domain"))

    // Core modules (data implements domain using these infrastructure cores)
    implementation(project(":core-common"))
    implementation(project(":core-network"))
    implementation(project(":core-database"))
    implementation(project(":core-ai"))
    implementation(project(":core-security"))

    // Retrofit / OkHttp
    implementation(libs.bundles.retrofit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    // Room
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)

    // Coroutines + Flow
    implementation(libs.bundles.coroutines)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation("androidx.work:work-testing:2.9.1")

    // DataStore (for ConnectivityObserver and preferences)
    implementation(libs.androidx.datastore.preferences)

    // Paging
    implementation(libs.androidx.paging.runtime)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.bundles.kotest)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.room.testing)
}
