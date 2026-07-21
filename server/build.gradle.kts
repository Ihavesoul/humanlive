import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Backend API — Kotlin/JVM + Ktor. The boring, single-language choice (ADR 0001).
// Owns: deterministic safety engine, programme rules, evidence catalog,
// and the LLM orchestrator that post-validates provider output. The provider
// is NEVER trusted to enforce safety (see specs/Safety_Threat_Model.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

// Compile on the host JDK, emit bytecode 17.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("dreamteam.server.ApplicationKt")
}

// The deterministic baseline plan + evidence catalog live in the repo-root
// `data/` dir (the PoC source of truth — see research/evidence_mapping.md).
// Embed them on the server classpath so the deterministic fallback (ADR 0001
// invariant #4) ships self-contained, with ONE copy of the data (no drift).
sourceSets {
    main {
        resources {
            srcDir(rootProject.layout.projectDirectory.dir("data"))
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
    // Encrypted-at-rest durable persistence behind the repository ports (ADR 0003 / DRE-16).
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}
