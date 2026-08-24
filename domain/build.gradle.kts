plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

// ─── Domain module ───────────────────────────────────────────────────────────
// Pure business logic module. Contains only Kotlin domain entities, repository
// interfaces, and use cases. Zero Android framework usage in source code —
// only the Gradle plugin is Android so we can depend on core-common (Android lib)
// and be consumed by data and feature modules.
android {
    namespace = "com.aiassistant.domain"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
// domain MUST NOT depend on data, feature, core-network, core-database, core-ai,
// core-security, or any Android framework.
configurations.all {
    resolutionStrategy.eachDependency {
        val forbidden = listOf(
            "com.aiassistant.data",
            "com.aiassistant.feature"
        )
        if (forbidden.any { requested.group.startsWith(it) }) {
            throw GradleException(
                "domain MUST NOT depend on '${requested.group}:${requested.name}'. " +
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
    // Only run JaCoCo when explicitly requested (e.g. in CI via -PenableJacoco).
    // This prevents OOM during local test runs where coverage reports aren't needed.
    if (project.hasProperty("enableJacoco")) {
        finalizedBy(tasks.withType<JacocoReport>())
    }
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
        "**/Manifest*.*"
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

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerificationConfig") {
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
    // domain depends ONLY on core-common (pure Kotlin entities/errors)
    implementation(project(":core-common"))

    // javax.inject for @Inject constructor annotations on use cases
    implementation("javax.inject:javax.inject:1")

    // Coroutines for Flow return types in repository interfaces
    implementation(libs.kotlinx.coroutines.core)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.kotest)
}
