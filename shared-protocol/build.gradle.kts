val serializationVersion: String by project
val junitVersion: String by project

dependencies {
    api(project(":core-domain"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$serializationVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

