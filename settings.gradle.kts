// DreamTeam — root Gradle settings. PWA-first (ADR 0001 / DRE-8).
// The client is the vanilla-JS PWA in app/ (not a Gradle module — it has no
// build step). Only the JVM tiers are built here:
//   :server       Kotlin/JVM Ktor backend (safety engine, repository layer, orchestrator)
//   :core:domain  Pure Kotlin/JVM domain model + deterministic logic (no Android dependency)
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "humanlive"

include(":server")
include(":core:domain")
