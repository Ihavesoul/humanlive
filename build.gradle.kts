// Root build — plugins declared here, applied in modules. JVM-only (ADR 0001).
// No Android/Compose plugins: the client is the static PWA in app/.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}
