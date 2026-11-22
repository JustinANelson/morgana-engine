package com.morgana.server.plugins

import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.ModuleRegistry
import com.morgana.server.EventHookRegistry
import com.morgana.server.MorganaServerHook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Plugin classes the test jars point at. They are on the test classpath, so the jar only needs a manifest. */
class RecordingPlugin : MorganaPlugin {
    override fun onEnable(context: PluginContext) {
        events += "enable:${context.pluginId}"
        context.registerHook(object : MorganaServerHook { override val pluginId = "hook-of-${context.pluginId}" })
        context.registerModule("market", "Market", "A test module")
        lastContext = context
    }
    override fun onDisable() { events += "disable" }

    companion object {
        val events = mutableListOf<String>()
        var lastContext: PluginContext? = null
    }
}

class ThrowingPlugin : MorganaPlugin {
    override fun onEnable(context: PluginContext) {
        context.registerHook(object : MorganaServerHook { override val pluginId = "half-registered" })
        context.registerModule("half", "Half")
        error("boom")
    }
}

class NotAPlugin

class PluginManagerTest {
    @TempDir lateinit var dir: Path

    private val hooks = EventHookRegistry()
    private val registry = ModuleRegistry()
    private var flags = ModuleFlags.ALL_ENABLED

    private fun manager() = PluginManager(dir, hooks, registry, { flags })

    private fun jar(file: String, manifest: String?) {
        JarOutputStream(Files.newOutputStream(dir.resolve(file))).use { out ->
            if (manifest != null) {
                out.putNextEntry(JarEntry(PluginManifest.FILE_NAME))
                out.write(manifest.toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun manifest(id: String, main: String = RecordingPlugin::class.java.name, api: Int = PLUGIN_API_VERSION, depends: String = "[]") =
        """{"id":"$id","name":"Plugin $id","version":"1.2.3","apiVersion":$api,"main":"$main","depends":$depends}"""

    @Test
    fun `a good jar is enabled and its hook and module are registered`() {
        RecordingPlugin.events.clear()
        jar("market.jar", manifest("market"))
        val m = manager()
        val result = m.loadAll()
        assertEquals(1, result.size)
        assertEquals(PluginStatus.ENABLED, result[0].status)
        assertEquals("1.2.3", result[0].version)
        assertEquals(listOf("hook-of-market"), hooks.pluginIds())
        assertEquals(listOf("market:market"), result[0].modules)
        assertNotNull(registry.get("market:market"))
        assertTrue(RecordingPlugin.lastContext!!.isModuleEnabled("market:market"))
        flags = ModuleFlags.fromDisabledKeys(listOf("market:market"))
        assertFalse(RecordingPlugin.lastContext!!.isModuleEnabled("market:market"))
        assertTrue(RecordingPlugin.lastContext!!.dataDir.toString().endsWith("market"))
        m.close()
    }

    @Test
    fun `disabling takes everything back and enabling restores it`() {
        jar("market.jar", manifest("market"))
        val m = manager()
        m.loadAll()
        assertEquals(listOf("market"), m.disable("market"))
        assertEquals(PluginStatus.DISABLED, m.list()[0].status)
        assertTrue(hooks.pluginIds().isEmpty())
        assertNull(registry.get("market:market"))
        assertEquals(PluginStatus.ENABLED, m.enable("market").status)
        assertEquals(1, hooks.pluginIds().size)
        m.close()
        assertTrue(hooks.pluginIds().isEmpty())
    }

    @Test
    fun `a plugin that throws while starting is rolled back and does not stop the others`() {
        jar("a-bad.jar", manifest("bad", ThrowingPlugin::class.java.name))
        jar("b-good.jar", manifest("good"))
        val m = manager()
        val byId = m.loadAll().associateBy { it.id }
        assertEquals(PluginStatus.FAILED, byId.getValue("bad").status)
        assertEquals("boom", byId.getValue("bad").problem)
        assertEquals(PluginStatus.ENABLED, byId.getValue("good").status)
        assertEquals(listOf("hook-of-good"), hooks.pluginIds())
        assertNull(registry.get("bad:half"))
        m.close()
    }

    @Test
    fun `unusable jars are reported with a reason`() {
        jar("nomanifest.jar", null)
        jar("badapi.jar", manifest("old", api = 99))
        jar("badid.jar", manifest("Bad Id"))
        jar("nomain.jar", manifest("lost", main = "com.example.Missing"))
        jar("notplugin.jar", manifest("plain", NotAPlugin::class.java.name))
        Files.writeString(dir.resolve("broken.jar"), "not a zip")
        val byFile = manager().loadAll().associateBy { it.file }
        assertTrue(byFile.getValue("nomanifest.jar").problem!!.contains("missing"))
        assertTrue(byFile.getValue("badapi.jar").problem!!.contains("plugin API 99"))
        assertTrue(byFile.getValue("badid.jar").problem!!.contains("id"))
        assertTrue(byFile.getValue("nomain.jar").problem!!.contains("was not found"))
        assertTrue(byFile.getValue("notplugin.jar").problem!!.contains("MorganaPlugin"))
        assertTrue(byFile.getValue("broken.jar").problem!!.contains("not a readable jar"))
        assertTrue(byFile.values.none { it.status == PluginStatus.ENABLED })
        assertTrue(hooks.pluginIds().isEmpty())
    }

    @Test
    fun `dependencies start first and a missing or failed one blocks the dependant`() {
        RecordingPlugin.events.clear()
        jar("a-app.jar", manifest("app", depends = """["base"]"""))
        jar("b-base.jar", manifest("base"))
        jar("c-orphan.jar", manifest("orphan", depends = """["nothing"]"""))
        jar("d-loop1.jar", manifest("loop1", depends = """["loop2"]"""))
        jar("e-loop2.jar", manifest("loop2", depends = """["loop1"]"""))
        val m = manager()
        val byId = m.loadAll().associateBy { it.id }
        assertEquals(listOf("enable:base", "enable:app"), RecordingPlugin.events)
        assertEquals(PluginStatus.ENABLED, byId.getValue("app").status)
        assertTrue(byId.getValue("orphan").problem!!.contains("not installed"))
        assertEquals(PluginStatus.FAILED, byId.getValue("loop1").status)
        assertEquals(PluginStatus.FAILED, byId.getValue("loop2").status)
        // Disabling the base takes its dependant down with it.
        assertEquals(setOf("app", "base"), m.disable("base").toSet())
        m.close()
    }

    @Test
    fun `reload picks up a changed jar`() {
        jar("market.jar", manifest("market"))
        val m = manager()
        m.loadAll()
        jar("market.jar", manifest("market").replace("1.2.3", "2.0.0"))
        assertEquals("2.0.0", m.reload("market").version)
        assertEquals(PluginStatus.ENABLED, m.list()[0].status)
        assertEquals(1, hooks.pluginIds().size)
        m.close()
    }

    @Test
    fun `a duplicate id is refused and inspect starts nothing`() {
        jar("a.jar", manifest("same"))
        jar("b.jar", manifest("same"))
        val inspected = PluginManager.inspect(dir)
        assertEquals(2, inspected.size)
        assertTrue(hooks.pluginIds().isEmpty())
        val result = manager().loadAll()
        assertEquals(1, result.count { it.status == PluginStatus.ENABLED })
        assertTrue(result.single { it.status == PluginStatus.FAILED }.problem!!.contains("already used"))
    }

    @Test
    fun `a missing plugins folder is fine`() {
        assertTrue(PluginManager(dir.resolve("nope"), hooks).loadAll().isEmpty())
    }
}
