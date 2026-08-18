pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.firebase.*")
                includeGroupByRegex("com\\.google\\.gms.*")
                includeGroupByRegex("com\\.google\\.testing.*")
                includeGroupByRegex("com\\.google\\.ar.*")
                includeGroupByRegex("com\\.google\\.mlkit.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    // gradle/libs.versions.toml is auto-loaded as the default 'libs' catalog by Gradle 8.x
    // No explicit create("libs") { from(...) } needed — that would call from() twice and fail.
}

rootProject.name = "DevelopMain_Android-AI"

// Application module
include(":app")

// Core modules
include(":core-common")
include(":core-ui")
include(":core-network")
include(":core-database")
include(":core-ai")
include(":core-security")

// Architecture modules
include(":domain")
include(":data")

// Feature modules
include(":feature-auth")
include(":feature-chat")
include(":feature-rag")
include(":feature-camera")
include(":feature-code")
include(":feature-voice")
include(":feature-settings")
include(":feature-profile")
include(":feature-history")
include(":feature-notes")
include(":feature-meeting")
include(":feature-resume")
include(":feature-email")
include(":feature-translator")
include(":feature-productivity")
include(":feature-on-device-ai")
include(":feature-search")
include(":feature-persona")
