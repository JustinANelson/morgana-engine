plugins {
    application
}

val nettyVersion: String by project
val slf4jVersion: String by project
val logbackVersion: String by project
val coroutinesVersion: String by project
val junitVersion: String by project
val bouncyCastleVersion: String by project

application {
    mainClass.set("com.morgana.server.MorganaServerMainKt")
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":shared-protocol"))
    implementation(project(":server-persistence"))
    implementation(project(":script-luak"))

    implementation("io.netty:netty-all:$nettyVersion")
    implementation("org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    runtimeOnly("ch.qos.logback:logback-classic:$logbackVersion")

    testImplementation(testFixtures(project(":core-domain")))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


// Pin data-relative paths (server-data/) to the repository root so server, desktop client, and content tools agree.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
tasks.withType<Test> {
    workingDir = rootProject.projectDir
}

fun registerContentTool(taskName: String, taskDescription: String, command: String) {
    tasks.register<JavaExec>(taskName) {
        group = "morgana"
        description = taskDescription
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("com.morgana.server.tools.ContentToolKt")
        workingDir = rootProject.projectDir
        // Extra tool flags, e.g.: .\gradlew.bat :server-app:validateContent -PcontentArgs="--json"
        args = listOf(command) + (findProperty("contentArgs")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList())
    }
}

registerContentTool("validateContent", "Validate all world content files (exit 1 on errors)", "validate")
registerContentTool("exportContentSchemas", "Export JSON Schemas for all content collections to docs/schemas", "schema")
registerContentTool("seedWorld", "Write the blank game's content into server-data/ if it has none", "seed")
registerContentTool("grantOwner", "List a username as a server Owner: -PcontentArgs=\"--user Alice\"", "owner")
registerContentTool("replayLog", "Replay a recorded session: -PcontentArgs=\"--file replays/session.json\"", "replay")
registerContentTool("exportWorldPack", "Export a validated world pack: -PcontentArgs=\"--out packs/mine.zip --name MyWorld\"", "pack-export")
registerContentTool("importWorldPack", "Import a world pack: -PcontentArgs=\"--file packs/mine.zip --mode merge\"", "pack-import")
registerContentTool("listWorlds", "List library worlds under worlds/", "worlds")
registerContentTool("newWorld", "Create a library world: -PcontentArgs=\"--name Camp [--pack packs/mine.zip]\"", "world-new")
registerContentTool("modules", "Show or set optional modules: -PcontentArgs=\"--disable trading,guilds\"", "modules")
registerContentTool("listPlugins", "Check the plugin jars in server-data/plugins: -PcontentArgs=\"--dir server-data\"", "plugins")
registerContentTool("storage", "Show or migrate the player-data backend: -PcontentArgs=\"--migrate sqlite\"", "storage")
registerContentTool("gameDefinition", "Validate game.json (or show the built-in Morgana Classic): -PcontentArgs=\"--write\" creates game.json from the built-in one", "game")
registerContentTool("newGame", "Generate a new custom game project template: -PcontentArgs=\"--name 'My Game' [--dir games/my-game]\"", "new-game")
registerContentTool("gameWiki", "Build the browsable game wiki: -PcontentArgs=\"--game games/roguelike [--out build/game-wiki/roguelike]\"", "game-wiki")
registerContentTool("validateArt", "Check a game's art folder: -PcontentArgs=\"--game games/my-game\" (all games when omitted)", "validate-art")
registerContentTool("validateScripts", "Validate all Lua script files: -PcontentArgs=\"--dir <scripts dir>\"", "validate-scripts")
registerContentTool("tlsInfo", "Show TLS mode, certificate fingerprint and an invite code: -PcontentArgs=\"--host play.example.com\"", "tls")

tasks.register<JavaExec>("stressTest") {
    group = "morgana"
    description = "Benchmark server tick time across ramping player counts: -PstressArgs=\"--counts 25,50,100,200 --ticks 400 --layout spread\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.morgana.server.tools.ServerStressToolKt")
    workingDir = rootProject.projectDir
    args = findProperty("stressArgs")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.named("check") {
    dependsOn(tasks.named("validateScripts"))
    dependsOn(tasks.named("validateArt"))
}
