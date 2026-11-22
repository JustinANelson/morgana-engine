val junitVersion: String by project
val slf4jVersion: String by project

// The only module that may depend on Luak (ADR 0013). The engine talks to scripting through its own port, added in phase 2;
// nothing outside this module may import net.blueva.luak.* so the runtime can be replaced or forked.
// The artifact is vendored and checksum-pinned in third-party/ and resolved from there only.
dependencies {
    implementation(project(":core-domain"))
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("net.blueva:luak-core-jvm:26.11")

    testImplementation(testFixtures(project(":core-domain")))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// A changed vendored file must stop the build before anything compiles against it.
tasks.configureEach {
    if (name == "compileKotlin" || name == "compileTestKotlin") dependsOn(rootProject.tasks.named("verifyThirdParty"))
}
tasks.named("check") { dependsOn(rootProject.tasks.named("verifyThirdParty")) }
