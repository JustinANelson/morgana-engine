---
type: Reference
title: "Build and Toolchain Versions"
description: "Pinned versions of Gradle, Kotlin, JDK, and every major library."
tags: [gradle, versions]
code: ["gradle.properties", "build.gradle.kts", "gradle/wrapper/gradle-wrapper.properties"]
symbols: ["gdxVersion", "nettyVersion", "jvmToolchain", "gradle-8.14.6", "verifyThirdParty"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Build and Toolchain Versions

| Item | Version | Defined in |
| :--- | :--- | :--- |
| Gradle wrapper | 8.14.6 (distribution checksum pinned; stay on 8.x, AGP 8.7.3 does not support Gradle 9) | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | 2.4.10 (plugins `jvm`, `android`, `serialization`); required by Luak, which is built with 2.4 and cannot be read by older compilers | root `build.gradle.kts` |
| JVM toolchain | 17 (all non-Android modules) | root `build.gradle.kts` |
| Android Gradle Plugin | 8.7.3 | root `build.gradle.kts` |
| kotlinx.serialization | 1.7.3 | `gradle.properties` |
| kotlinx.coroutines | 1.9.0 | `gradle.properties` |
| LibGDX | 1.14.2 (needed by gdx-teavm; Android pins androidx.core 1.15.0 to stay on compileSdk 35) | `gradle.properties` |
| KTX | 1.13.1-rc1 | `gradle.properties` |
| VisUI | 1.5.9 | `gradle.properties` |
| Netty | 4.1.115.Final | `gradle.properties` |
| Luak (Lua 5.5.1 for Kotlin) | 26.11, vendored and checksum-pinned (JVM core artifact only) | `third-party/`, `script-luak/build.gradle.kts` |
| SLF4J / Logback | 2.0.16 / 1.5.12 | `gradle.properties` |
| JUnit | 5.11.3 | `gradle.properties` |

`org.gradle.caching=true` and `org.gradle.parallel=true` are on: a cached `test` task can look "up to date"; see [testing strategy](/reference/testing_strategy.md).
Project group is `com.morgana`; version `0.1.0-SNAPSHOT`.

## Vendored third-party code
Luak is checked in under `third-party/` (jar, metadata, sources, license, `checksums.sha256`, provenance in `third-party/README.md`) and resolved from there
only: the root `build.gradle.kts` declares that folder as an `exclusiveContent` Maven repository for the `net.blueva` group. `.\gradlew.bat :verifyThirdParty` checks every file against
the recorded SHA-256, and `:script-luak` will not compile if one differs. `.gitattributes` marks the folder `-text` so line-ending conversion cannot change the bytes.
Only `:script-luak` depends on it, and no other module may import `net.blueva.luak` ([ADR 0013](/decisions/0013_engine_game_split_and_lua_scripting.md)).

## Upgrade notes
* Moving to Kotlin 2.4 needed one build-script change: the Android module's `kotlinOptions { jvmTarget }` block became `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`.
* Kotlin 2.5 will require Gradle 8.14.4 or newer, which the wrapper already satisfies.

