#!/usr/bin/env bash
# Build the native :app debug APK pinned to JDK 17.
#
# Why: AGP 8.13's JdkImageTransform runs `jlink` on the Gradle JVM to build the
# android JDK image from core-for-system-modules.jar. On JDK >=21 (the macOS host
# default is 26) jlink fails the `:app:androidJdkImage` transform, so the whole
# :app:assembleDebug build breaks. JDK 17 is also the project's compile/kotlin
# target (app/build.gradle.kts). CI pins the same via actions/setup-java — see
# .github/workflows/ci.yml. (DRE-15)
set -euo pipefail

resolve_jdk17() {
  # 1. Explicit override.
  [ -n "${JAVA17_HOME:-}" ] && { printf '%s\n' "$JAVA17_HOME"; return 0; }
  # 2. Common install locations (macOS homebrew, Linux distro, macOS temurin).
  for d in \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/usr/lib/jvm/java-17-openjdk-amd64" \
    "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"; do
    [ -x "$d/bin/java" ] && { printf '%s\n' "$d"; return 0; }
  done
  return 1
}

if ! JAVA_HOME="$(resolve_jdk17)"; then
  echo "ERROR: JDK 17 not found. Set JAVA17_HOME to a JDK 17 JAVA_HOME." >&2
  exit 1
fi
export JAVA_HOME
echo "Using JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)  ($JAVA_HOME)"

cd "$(dirname "$0")/.."
./gradlew :app:assembleDebug --no-daemon "$@"
