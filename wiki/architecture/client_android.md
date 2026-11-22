---
type: Module
title: "Module: client-android"
description: "The Android launcher with LibGDX natives packaging and emulator loopback defaults."
tags: [module, client, android]
code: ["client-android/src/main", "client-android/build.gradle.kts"]
symbols: ["DEFAULT_EMULATOR_HOST", "copyAndroidNatives", "minSdk"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Module: `:client-android`

- `AndroidLauncher` activity; default host is `10.0.2.2` (the emulator's alias for the host PC's `127.0.0.1:7001`).
- Gradle: `compileSdk`/`targetSdk` 35, `minSdk` 24, Java/Kotlin target 17.
- A `copyAndroidNatives` task unpacks LibGDX natives for `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` into generated `jniLibs` before `preBuild`.
- Touch controls (virtual D-pad and action pad) live in `client-core`'s HUD ([input and HUD](/client/input_and_hud.md)).
- Build: `.\gradlew.bat :client-android:assembleDebug`. Android lint runs as part of `check`.
