plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}

val gdxVersion: String by project
val junitVersion: String by project
val natives: Configuration by configurations.creating

val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")

android {
    namespace = "com.morgana.client.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.morgana.client.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
            jniLibs.srcDirs(generatedJniLibsDir)
        }
        named("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":client-net-netty"))
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    // libGDX 1.14.2 pulls androidx.core 1.17 (needs compileSdk 36 / newer AGP); keep the Android toolchain as is.
    constraints {
        implementation("androidx.core:core") { version { strictly("1.15.0") }; because("1.16+ requires compileSdk 36") }
    }

    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86_64")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val copyAndroidNatives by tasks.registering {
    inputs.files(natives)
    outputs.dir(generatedJniLibsDir)
    doLast {
        val outBase = generatedJniLibsDir.get().asFile
        val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        abis.forEach { abi -> File(outBase, abi).mkdirs() }

        natives.files.forEach { jar ->
            val targetAbi = abis.firstOrNull { abi -> jar.name.endsWith("natives-$abi.jar") }
            if (targetAbi != null) {
                copy {
                    from(zipTree(jar))
                    into(File(outBase, targetAbi))
                    include("*.so")
                }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(copyAndroidNatives)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
