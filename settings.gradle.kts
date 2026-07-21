// DreamTeam — root Gradle settings. Native-only (ADR 0002 / DRE-9).
// The product client is the native Android Compose app in app/ (:app).
// Built tiers:
//   :app          Android + Jetpack Compose (the product client)
//   :server       Kotlin/JVM Ktor backend (safety engine, repository layer, orchestrator)
//   :core:domain  Pure Kotlin/JVM domain model + deterministic logic (shared by :app + :server)
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "humanlive"

include(":app")
include(":server")
include(":core:domain")
