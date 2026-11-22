val slf4jVersion: String by project

// An example server plugin (see wiki/playbooks/write_a_server_plugin.md). It compiles against the server but is never shipped
// inside it: build the jar with :plugin-example:jar and copy it into <data dir>/plugins/.
dependencies {
    compileOnly(project(":server-app"))
    compileOnly(project(":core-domain"))
    compileOnly(project(":shared-protocol"))
    compileOnly("org.slf4j:slf4j-api:$slf4jVersion")
}

tasks.named<Jar>("jar") {
    archiveFileName.set("hello-plugin.jar")
}
