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

// Toolchain resolver so Gradle can auto-provision a JDK matching the daemon
// JVM criteria (gradle/gradle-daemon-jvm.properties). AGP's androidJdkImage/jlink
// transform breaks on JDK 25/26, so the daemon is pinned to JDK 17 (DRE-58);
// Foojay downloads it when no local JDK 17 is auto-detected (e.g. Homebrew paths).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
