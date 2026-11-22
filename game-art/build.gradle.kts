plugins {
    application
}

val junitVersion: String by project

// Art generators and exporters for the bundled games (the dungeon crawler, the open-world RPG, the action RPG). The engine
// never depends on this module: it holds the per-game sprite kits, specs and exporters that sit on top of the generic
// `:tilegen` generators, plus the `:game-art:run` command line for them. Games are data; this is only the tooling that
// draws their art.
application {
    mainClass.set("com.morgana.gameart.cli.GameArtCliKt")
}

dependencies {
    implementation(project(":tilegen"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
