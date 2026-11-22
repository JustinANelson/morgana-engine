// Assembles the Morgana ORPG SDK: a self-contained folder (and zip) other developers unpack and run with only Java 17+.
//   .\gradlew.bat :distribution:sdk      -> distribution/build/sdk/morgana-sdk-<version>/
//   .\gradlew.bat :distribution:sdkZip   -> distribution/build/distributions/morgana-sdk-<version>.zip
// Layout: bin/ (launchers for Windows and macOS/Linux), lib/ (every jar), web/ (the browser client, served by morgana-server --web),
// games/ (every game folder), docs/, licenses/, README.md.

import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens

dependencies {
    // The desktop client depends on the server, persistence, scripting and tools, so its runtime classpath is everything.
    implementation(project(":client-desktop"))
}

val sdkName = "morgana-sdk-${project.version}"
val sdkDir = layout.buildDirectory.dir("sdk/$sdkName")

/** One launcher per entry point: (file name, main class, description, opens a window). */
data class Launcher(val name: String, val main: String, val description: String, val window: Boolean = false)

val launchers = listOf(
    Launcher("morgana-client", "com.morgana.client.desktop.DesktopLauncherKt", "Morgana desktop client (realm browser, Local Worlds, F1 editors).", window = true),
    Launcher("morgana-server", "com.morgana.server.MorganaServerMainKt", "Morgana dedicated server. Options: --port <n> --game <dir> --data <dir> --web (also serve the browser client, for phones) --ws-port <n>."),
    Launcher("morgana", "com.morgana.server.tools.ContentToolKt", "Morgana command-line tools (new-game, world-new, validate, owner, modules, tls, ...)."),
    Launcher("morgana-art", "com.morgana.client.desktop.GameArtExporter", "Export the built-in art a game uses into its assets/ folder as editable PNGs.", window = false),
    Launcher("morgana-tileset", "com.morgana.client.desktop.GenerateTilesetTool", "Generate 32x32 tilesets for a game.", window = false)
)

val sdk by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assemble the Morgana SDK folder (launchers, jars, blank and reference roguelike games, docs) in distribution/build/sdk/."
    into(sdkDir)

    into("lib") {
        from(configurations.runtimeClasspath)
    }
    for (l in launchers) {
        val tokens = mapOf(
            "MAIN" to l.main, "DESCRIPTION" to l.description, "VERSION" to project.version.toString(),
            "NEEDS_WINDOW" to if (l.window) "yes" else "no"
        )
        into("bin") {
            from("src/bin/launcher.bat") { rename { "${l.name}.bat" } }
            filter<ReplaceTokens>("tokens" to tokens)
            filter(mapOf("eol" to FixCrLfFilter.CrLf.newInstance("crlf")), FixCrLfFilter::class.java)
        }
        into("bin") {
            from("src/bin/launcher.sh") { rename { l.name } }
            filter<ReplaceTokens>("tokens" to tokens)
            filter(mapOf("eol" to FixCrLfFilter.CrLf.newInstance("lf")), FixCrLfFilter::class.java)
            filePermissions { unix("rwxr-xr-x") }
        }
    }
    from("src/root") {
        filter<ReplaceTokens>("tokens" to mapOf("VERSION" to project.version.toString()))
    }
    // The minified browser client: morgana-server --web serves it, so phones and other computers just open a page.
    dependsOn(":client-web:gdx_teavm_web_js_release_build", ":client-web:copyPwaFilesRelease")
    into("web") { from(project(":client-web").layout.buildDirectory.dir("dist/js/release/webapp")) }
    // Ship only the public reference games (exclude developer games and any local tools/ folders).
    into("games") {
        from(rootProject.file("games")) {
            exclude("**/tools/**", "halcyon-reach/**")
        }
    }
    into("docs") {
        from(rootProject.file("docs")) {
            include(
                "GETTING_STARTED.md", "CREATOR_GUIDE.md", "API_REFERENCE.md", "GAME_DEFINITION.md",
                "GENRES_AND_MODULES.md", "PLUGIN_ARCHITECTURE.md", "schemas/**"
            )
        }
    }
    into("licenses") { from(rootProject.file("third-party/licenses")) }
    // Empty folders the tools and the client expect, so a first look at the SDK shows where things go.
    doLast {
        listOf("worlds", "server-data").forEach { sdkDir.get().dir(it).asFile.mkdirs() }
    }
}

val sdkZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zip the Morgana SDK for sharing: distribution/build/distributions/morgana-sdk-<version>.zip."
    dependsOn(sdk)
    archiveFileName.set("$sdkName.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(sdkDir) { into(sdkName) }
}
