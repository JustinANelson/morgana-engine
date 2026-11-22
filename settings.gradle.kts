pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // Automatically downloads Eclipse Temurin JDK 17 when a developer runs Gradle on another JDK (e.g. Java 21+).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "morgana-orpg"

include(
    ":core-domain",
    ":shared-protocol",
    ":server-persistence",
    ":server-app",
    ":tilegen",
    ":game-art",
    ":game-tests",
    ":client-core",
    ":client-net-netty",
    ":script-luak",
    ":client-web",
    ":client-desktop",
    ":client-android",
    ":plugin-example",
    ":dev-tools",
    ":distribution"
)
