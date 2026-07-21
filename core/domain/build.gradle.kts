import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM domain model. No Android dependency — buildable & testable
// with the JDK alone. Safety-critical deterministic logic lives here.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Compile on the host JDK, emit bytecode 17 (broad Android/server compat).
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The PoC canonical exercise library lives in the repo-root `data/` dir (the
// single source of truth — server embeds the same dir on its main classpath).
// Put it on the *test* classpath only (not published with the domain artifact)
// so MovementTagsCoverageTest can read /exercises.json without a working-dir
// dependency. ONE copy of the data — no drift (DRE-41).
sourceSets {
    test {
        resources {
            srcDir(rootProject.layout.projectDirectory.dir("data"))
        }
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}
