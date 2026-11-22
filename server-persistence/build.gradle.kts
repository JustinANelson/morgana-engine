val serializationVersion: String by project
val junitVersion: String by project
val sqliteVersion: String by project

dependencies {
    api(project(":core-domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("org.xerial:sqlite-jdbc:$sqliteVersion")

    testImplementation(testFixtures(project(":core-domain")))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

