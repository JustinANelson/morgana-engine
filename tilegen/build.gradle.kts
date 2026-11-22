plugins {
    application
}

val junitVersion: String by project

// Procedural pixel-art generation: overworld and themed tile sets, autotiles, props and structures, paper-doll
// characters and monsters, JSON tileset specs, and a command-line tool (`:tilegen:run`). Pure Kotlin: no LibGDX or other
// engine dependency, so desktop, Android, the TeaVM web build and plain unit tests can all use the generators. Images are
// TileImage (packed RGBA8888); client-core converts them to Pixmaps (TileImagePixmaps.kt). Only the `cli` and `export`
// packages touch the file system.
application {
    mainClass.set("com.morgana.tilegen.cli.TilesetCliKt")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// `.\gradlew.bat :tilegen:run --args="..."` runs from the repository root so relative `--out` paths land where the caller
// expects; `-q` hides Gradle's own chatter.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
