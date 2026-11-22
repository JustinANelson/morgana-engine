pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
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
