val gdxVersion: String by project
val ktxVersion: String by project
val visUiVersion: String by project
val coroutinesVersion: String by project
val junitVersion: String by project

dependencies {
    api(project(":core-domain"))
    api(project(":shared-protocol"))
    api(project(":tilegen"))

    api("com.badlogicgames.gdx:gdx:$gdxVersion")
    api("io.github.libktx:ktx-app:$ktxVersion")
    api("io.github.libktx:ktx-scene2d:$ktxVersion")
    api("io.github.libktx:ktx-actors:$ktxVersion")
    api("io.github.libktx:ktx-graphics:$ktxVersion")
    api("io.github.libktx:ktx-style:$ktxVersion")
    api("com.kotcrab.vis:vis-ui:$visUiVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    testImplementation(testFixtures(project(":core-domain")))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

