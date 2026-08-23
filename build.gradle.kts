// Top-level build file — configuration here applies to all subprojects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

// ─── Detekt ──────────────────────────────────────────────────────────────────
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    // Scan all modules — exclude build-logic (convention plugins use default package)
    source.setFrom(
        fileTree(rootDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**")
            exclude("**/.gradle/**")
            exclude("**/build-logic/**")
        }
    )
    parallel = true
}

dependencies {
    // Detekt plugins
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
}

// ─── ktlint ──────────────────────────────────────────────────────────────────
ktlint {
    version.set("1.3.1")
    android.set(true)
    outputColorName.set("RED")
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.SARIF)
    }
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
        include("**/kotlin/**")
        include("**/java/**")
    }
}

// Apply ktlint to all subprojects
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    ktlint {
        version.set("1.3.1")
        android.set(true)
    }
}

// Repositories are configured globally via dependencyResolutionManagement in settings.gradle.kts.
// No per-project repository declarations needed (FAIL_ON_PROJECT_REPOS mode is active).

// ─── Test JVM memory ─────────────────────────────────────────────────────────
// The default forked test JVM heap (512 MB) causes OutOfMemoryError when many
// modules run sequentially in the same Gradle invocation. Raise the heap for
// every unit-test task across all subprojects.
subprojects {
    tasks.withType<Test>().configureEach {
        maxHeapSize = "1536m"
        forkEvery = 20
        jvmArgs("-XX:MaxMetaspaceSize=512m", "-Dfile.encoding=UTF-8")
    }
}
