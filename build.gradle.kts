plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("jvm") version "2.4.10" apply false
    kotlin("android") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
}

allprojects {
    group = "com.morgana"
    version = "0.1.0-SNAPSHOT"

    repositories {
        // Vendored, checksum-pinned third-party libraries (see third-party/README.md). Exclusive: the net.blueva group is
        // never resolved from anywhere else, and nothing else is resolved from here.
        exclusiveContent {
            forRepository { maven(url = uri(rootDir.resolve("third-party/maven"))) }
            filter { includeGroup("net.blueva") }
        }
        mavenCentral()
        google()
    }
}

val verifyThirdParty by tasks.registering {
    group = "verification"
    description = "Checks every vendored third-party file against third-party/checksums.sha256."
    val base = layout.projectDirectory.dir("third-party")
    val sums = base.file("checksums.sha256")
    inputs.dir(base)
    doLast {
        val problems = mutableListOf<String>()
        sums.asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            val expected = parts[0]
            val relative = parts[1]
            val file = base.file(relative).asFile
            if (!file.isFile) {
                problems += "missing: $relative"
            } else {
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                if (actual != expected) problems += "changed: $relative (expected $expected, found $actual)"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Vendored third-party files do not match third-party/checksums.sha256:\n" + problems.joinToString("\n"))
        }
    }
}

val distributables by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Build all project distributables (Morgana SDK zip, Android debug APK, and web client zip) into build/distributables/."
    dependsOn(":distribution:sdkZip", ":client-android:assembleDebug", ":client-web:dist")
    from(project(":distribution").layout.buildDirectory.dir("distributions")) {
        include("*.zip")
    }
    from(project(":client-android").layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
    }
    from(project(":client-web").layout.buildDirectory.dir("dist")) {
        include("*.zip")
    }
    into(layout.buildDirectory.dir("distributables"))
    doLast {
        val outDir = layout.buildDirectory.dir("distributables").get().asFile
        logger.lifecycle("Distributables written to ${outDir.absolutePath}:")
        outDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            logger.lifecycle("  - ${file.name} (${file.length() / (1024 * 1024)} MB)")
        }
    }
}

subprojects {
    if (name != "client-android") {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            System.getProperty("MORGANA_REGEN_GOLDEN")?.let { systemProperty("MORGANA_REGEN_GOLDEN", it) }
            System.getenv("MORGANA_REGEN_GOLDEN")?.let { environment("MORGANA_REGEN_GOLDEN", it) }
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
