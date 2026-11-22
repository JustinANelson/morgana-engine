plugins {
    application
}

val serializationVersion: String by project
val junitVersion: String by project

// Tooling, not engine: compiles map recipes (JSON: terrain, paths, walls, props, warps, spawn camps) into map documents using
// the tile lookup a tileset export wrote (`tilesets.index.json`). Maps stay plain content files; a recipe is one way to make
// them, the F1 editor is another, and both go through the same validation. The engine never depends on this module.
application {
    mainClass.set("com.morgana.mapbuilder.MapBuilderCliKt")
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":server-persistence"))
    implementation(project(":tilegen"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
tasks.withType<Test> {
    workingDir = rootProject.projectDir
}
