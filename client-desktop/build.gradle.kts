plugins {
    application
}

val gdxVersion: String by project
val junitVersion: String by project

application {
    mainClass.set("com.morgana.client.desktop.DesktopLauncherKt")
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":client-net-netty"))
    implementation(project(":server-app"))
    implementation(project(":server-persistence"))
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// Writes the built-in procedural art into a game's assets/ folder: .\gradlew.bat :client-desktop:exportGameArt -PartArgs="--game games/blank"
tasks.register<JavaExec>("exportGameArt") {
    group = "morgana"
    description = "Export the built-in procedural tileset and the sprites a game uses to its assets/ folder as editable PNGs"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.morgana.client.desktop.GameArtExporter")
    workingDir = rootProject.projectDir
    args = (findProperty("artArgs")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList())
}

// Procedurally generates 32x32 texture tilesets: .\gradlew.bat :client-desktop:generateTileset -PtilesetArgs="--game games/blank --id 1 --rows 3 --seed 1337"
tasks.register<JavaExec>("generateTileset") {
    group = "morgana"
    description = "Procedurally generate 32x32 texture tilesets for a game"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.morgana.client.desktop.GenerateTilesetTool")
    workingDir = rootProject.projectDir
    args = (findProperty("tilesetArgs")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList())
}
