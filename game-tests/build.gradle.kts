val coroutinesVersion: String by project
val junitVersion: String by project

// Tests of the bundled games' content (the open-world RPG, the action RPG, the dungeon crawler, the tutorial village).
// They load a game folder under games/ (on a temp copy) and check its data, art, balance and rules against the engine.
// Kept apart from the engine modules so engine tests never depend on one particular game.
dependencies {
    testImplementation(project(":core-domain"))
    testImplementation(testFixtures(project(":core-domain")))
    testImplementation(project(":shared-protocol"))
    testImplementation(project(":server-persistence"))
    testImplementation(project(":server-app"))
    testImplementation(project(":script-luak"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    workingDir = rootProject.projectDir
}
