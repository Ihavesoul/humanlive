// Native Android product client — Jetpack Compose (ADR 0002 / DRE-9).
// This is the ONLY delivery surface: no PWA, no TWA/Capacitor, no web client.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dreamteam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dreamteam.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // Offline-first evidence catalog (M6-A / DRE-67): the repo-root `data/` dir
    // is the single source of truth for the catalog (ADR 0001). Bundle it as
    // Android *assets* so the app reads evidence_catalog.json offline via
    // AssetManager (loadEvidenceResolver), and onto the *test* classpath so the
    // pure resolver's JVM unit test reads it via getResourceAsStream — one copy
    // of the data, no drift (the same one-line srcDir pattern the server uses
    // for its classpath resource).
    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.layout.projectDirectory.dir("data"))
        }
        getByName("test") {
            resources.srcDir(rootProject.layout.projectDirectory.dir("data"))
        }
    }
    // JVM unit tests for client→domain wiring (DRE-52). Run as plain JUnit 5 on
    // the host JDK with a stubbed android.jar — no device/Robolectric needed,
    // because the unit under test (ClientAdaptation) is pure Kotlin with no
    // Android runtime dependency.
    testOptions {
        unitTests {
            all { it.useJUnitPlatform() }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The native client consumes the shared deterministic safety/domain logic
    // (ADR 0002 / DRE-9): plan generation runs the SAME SafetyGuardedGateway
    // offline-first, with no server round-trip required.
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Client→domain wiring unit tests (DRE-52): JUnit 5 + Kotest matchers.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions.core)
}
