import org.teavm.gradle.api.OptimizationLevel

plugins {
    id("com.github.xpenatan.gdx-teavm") version "1.6.2"
}

val gdxVersion: String by project

// Browser client: the shared :client-core compiled to JavaScript by TeaVM (gdx-teavm backend).
// The server must have its WebSocket port enabled (MORGANA_WS_PORT), see wiki/client/web_client.md.
dependencies {
    implementation(project(":client-core"))
    implementation("org.teavm:teavm-jso-apis:0.15.0")
}

// Every class VisUI's skin JSON names. The skin creates them by name at run time, so TeaVM cannot see the use and would
// drop the ones our own code never touches; the reflection policy grants access but does not keep them.
val skinClasses = listOf(
    "com.badlogic.gdx.graphics.Color",
    "com.badlogic.gdx.graphics.g2d.BitmapFont",
    "com.badlogic.gdx.scenes.scene2d.ui.Button\$ButtonStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.CheckBox\$CheckBoxStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.ImageButton\$ImageButtonStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.ImageTextButton\$ImageTextButtonStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.Label\$LabelStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.List\$ListStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.ProgressBar\$ProgressBarStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.ScrollPane\$ScrollPaneStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.SelectBox\$SelectBoxStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.Skin\$TintedDrawable",
    "com.badlogic.gdx.scenes.scene2d.ui.Slider\$SliderStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.SplitPane\$SplitPaneStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.TextButton\$TextButtonStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.TextField\$TextFieldStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.TextTooltip\$TextTooltipStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.Touchpad\$TouchpadStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.Tree\$TreeStyle",
    "com.badlogic.gdx.scenes.scene2d.ui.Window\$WindowStyle",
    "com.kotcrab.vis.ui.Sizes",
    "com.kotcrab.vis.ui.util.adapter.SimpleListAdapter\$SimpleListAdapterStyle",
    "com.kotcrab.vis.ui.util.form.SimpleFormValidator\$FormValidatorStyle",
    "com.kotcrab.vis.ui.widget.BusyBar\$BusyBarStyle",
    "com.kotcrab.vis.ui.widget.LinkLabel\$LinkLabelStyle",
    "com.kotcrab.vis.ui.widget.ListViewStyle",
    "com.kotcrab.vis.ui.widget.Menu\$MenuStyle",
    "com.kotcrab.vis.ui.widget.MenuBar\$MenuBarStyle",
    "com.kotcrab.vis.ui.widget.MenuItem\$MenuItemStyle",
    "com.kotcrab.vis.ui.widget.MultiSplitPane\$MultiSplitPaneStyle",
    "com.kotcrab.vis.ui.widget.PopupMenu\$PopupMenuStyle",
    "com.kotcrab.vis.ui.widget.Separator\$SeparatorStyle",
    "com.kotcrab.vis.ui.widget.Tooltip\$TooltipStyle",
    "com.kotcrab.vis.ui.widget.VisCheckBox\$VisCheckBoxStyle",
    "com.kotcrab.vis.ui.widget.VisImageButton\$VisImageButtonStyle",
    "com.kotcrab.vis.ui.widget.VisImageTextButton\$VisImageTextButtonStyle",
    "com.kotcrab.vis.ui.widget.VisSplitPane\$VisSplitPaneStyle",
    "com.kotcrab.vis.ui.widget.VisTextButton\$VisTextButtonStyle",
    "com.kotcrab.vis.ui.widget.VisTextField\$VisTextFieldStyle",
    "com.kotcrab.vis.ui.widget.color.ColorPickerStyle",
    "com.kotcrab.vis.ui.widget.color.ColorPickerWidgetStyle",
    "com.kotcrab.vis.ui.widget.file.FileChooserStyle",
    "com.kotcrab.vis.ui.widget.spinner.Spinner\$SpinnerStyle",
    "com.kotcrab.vis.ui.widget.tabbedpane.TabbedPane\$TabbedPaneStyle",
    "com.kotcrab.vis.ui.widget.toast.Toast\$ToastStyle"
)

gdxTeaVM {
    assets("assets")
    // VisUI loads its skin from the classpath; the browser has no classpath, so ship it as files. Only the 1x skin: MorganaGame
    // calls VisUI.load() with the default scale.
    classpathAssets("com/kotcrab/vis/ui/skin/x1")

    webDefaults {
        mainClass = "com.morgana.client.web.WebLauncherKt"
        htmlTitle = "Morgana ORPG"
        htmlWidth = 1280
        htmlHeight = 768
        optimization = OptimizationLevel.BALANCED
        obfuscated = false
    }

    js {
        serverPort = 8080
        skinClasses.forEach { preservedClasses.add(it) }
    }

    // Minified build for deployment: gdx_teavm_web_js_release_build -> build/dist/js/release
    js("release") {
        obfuscated = true
        skinClasses.forEach { preservedClasses.add(it) }
    }
}

// Installable-app files (manifest, service worker, icons from client-web/pwa) added to each built page. The page registers
// them itself at run time (see BrowserDevice.kt), because the generated index.html is not ours to edit.
fun registerPwaCopy(name: String, outputDir: String) = tasks.register<Copy>(name) {
    from(layout.projectDirectory.dir("pwa"))
    into(layout.buildDirectory.dir(outputDir))
}
val copyPwaDev = registerPwaCopy("copyPwaFiles", "dist/js/webapp")
val copyPwaRelease = registerPwaCopy("copyPwaFilesRelease", "dist/js/release/webapp")
tasks.matching { it.name == "gdx_teavm_web_js_build" }.configureEach { finalizedBy(copyPwaDev) }
tasks.matching { it.name == "gdx_teavm_web_js_release_build" }.configureEach { finalizedBy(copyPwaRelease) }

tasks.register<Zip>("dist") {
    group = "distribution"
    description = "Packages the minified release web client into client-web/build/dist/morgana-realms-client-html.zip for itch.io / static hosting."
    dependsOn("gdx_teavm_web_js_release_build", copyPwaRelease)
    from(layout.buildDirectory.dir("dist/js/release/webapp")) {
        exclude("WEB-INF/**")
    }
    archiveBaseName.set("morgana-realms-client-html")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
}

// Headless-browser smoke test (smoke-test/, see wiki/playbooks/browser_smoke_test.md). Not part of `check`: it needs Node and
// Chrome (or Edge) installed. Run: .\gradlew.bat :client-web:smokeTest
val smokeDir = rootProject.file("smoke-test")
val npm = if (System.getProperty("os.name").lowercase().contains("windows")) listOf("cmd", "/c", "npm") else listOf("npm")

val smokeTestInstall = tasks.register<Exec>("smokeTestInstall") {
    group = "verification"
    description = "Installs the smoke test's Node dependencies (npm ci)"
    workingDir = smokeDir
    commandLine(npm + listOf("ci", "--no-audit", "--no-fund"))
    inputs.files(smokeDir.resolve("package.json"), smokeDir.resolve("package-lock.json"))
    outputs.dir(smokeDir.resolve("node_modules"))
}

tasks.register<Exec>("smokeTest") {
    group = "verification"
    description = "Builds the web client and server, then drives the client in headless Chrome (screenshots in build/smoke-test)"
    dependsOn(smokeTestInstall, "gdx_teavm_web_js_build", ":server-app:installDist", ":plugin-example:jar")
    workingDir = smokeDir
    commandLine("node", "run.mjs")
}

tasks.register<Exec>("uiAudit") {
    group = "verification"
    description = "Screenshots every screen, the New Realm Wizard, and all F1 editor panels, checking for text/window overflows (build/ui-audit/)"
    dependsOn(smokeTestInstall, "gdx_teavm_web_js_build", ":server-app:installDist")
    workingDir = smokeDir
    commandLine("node", "ui-audit.mjs")
}
