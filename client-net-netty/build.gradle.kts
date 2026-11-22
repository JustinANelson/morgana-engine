val nettyVersion: String by project
val junitVersion: String by project

// The Netty TCP implementation of ClientTransport, shared by the desktop and Android launchers.
dependencies {
    api(project(":client-core"))
    implementation("io.netty:netty-all:$nettyVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
