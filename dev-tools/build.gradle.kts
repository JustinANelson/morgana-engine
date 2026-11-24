val junitVersion: String by project

// Repository tooling (wiki lint/index/impact). Depends on nothing else in the project.
dependencies {
    implementation("org.yaml:snakeyaml:2.3")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

fun registerWikiTool(taskName: String, taskDescription: String, command: String) {
    tasks.register<JavaExec>(taskName) {
        group = "morgana"
        description = taskDescription
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("com.morgana.devtools.wiki.WikiToolKt")
        workingDir = rootProject.projectDir
        // Extra flags, e.g.: .\gradlew.bat :dev-tools:wikiImpact -PwikiArgs="--git HEAD~1"
        args = listOf(command) + (findProperty("wikiArgs")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList())
    }
}

registerWikiTool("wikiLint", "Lint the OKF wiki: conformance, links, code references, index freshness", "lint")
registerWikiTool("wikiIndex", "Regenerate wiki/index.md from page frontmatter", "index")
registerWikiTool("wikiImpact", "List wiki pages affected by changed source files (-PwikiArgs=\"--git <ref>\" or file paths)", "impact")

// EngineBoundaryTest reads the engine's source files, which Gradle cannot see as inputs: without this declaration the test counted as
// up to date and silently did not re-run when a module's sources changed (a java.time import in :core-domain got through for a day).
tasks.test {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("*/src/main/**/*.kt", "*/src/main/**/*.java", "*/build.gradle.kts")
        }
    ).withPropertyName("engineSources")
}

// The repository verification gate includes the wiki.
tasks.named("check") {
    dependsOn("wikiLint")
}
