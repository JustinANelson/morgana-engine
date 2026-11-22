plugins {
    `java-test-fixtures`
}

val serializationVersion: String by project
val junitVersion: String by project

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

