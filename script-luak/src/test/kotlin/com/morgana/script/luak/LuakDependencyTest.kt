package com.morgana.script.luak

import net.blueva.luak.Budget
import net.blueva.luak.Globals
import net.blueva.luak.LuaError
import net.blueva.luak.LuaValue
import net.blueva.luak.lib.LuaPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Phase 0 smoke test: the vendored Luak resolves, runs, and its sandbox controls work. The full sandbox suite (the adversarial
 * cases from the investigation) belongs to the script host in phase 2; this only proves the dependency is usable and pinned.
 */
class LuakDependencyTest {

    /** A lane with the outside world removed, the minimum every sandbox profile starts from. */
    private fun lane(): Globals {
        val g = LuaPlatform.standardGlobals()
        for (name in listOf("io", "os", "package", "require", "dofile", "loadfile", "load", "loadstring", "debug")) g.set(name, LuaValue.NIL)
        g.textonly = true
        return g
    }

    private fun run(g: Globals, source: String): String = g.load(source)!!.call()!!.tojstring()

    private fun luaError(g: Globals, source: String): String =
        try {
            g.load(source)!!.call()
            fail("expected a LuaError for: $source")
        } catch (e: LuaError) {
            e.message ?: ""
        }

    @Test
    fun `the library comes from the vendored repository, not the network`() {
        val location = LuaPlatform::class.java.protectionDomain.codeSource.location.path.replace('\\', '/')
        assertTrue("/third-party/maven/" in location, "Luak must resolve from third-party/maven, but loaded from $location")
    }

    @Test
    fun `a script runs and returns a value`() {
        assertEquals("42", run(lane(), "return 6 * 7"))
    }

    @Test
    fun `the outside world is not reachable from a sandboxed lane`() {
        val g = lane()
        assertEquals("nil", run(g, "return tostring(io)"))
        assertTrue("attempt to index a nil value" in luaError(g, "os.exit(1)"))
    }

    @Test
    fun `a runaway loop is stopped by the instruction budget`() {
        val g = lane().also { it.budget = Budget().apply { instructions = 1_000_000 } }
        assertTrue("budget" in luaError(g, "while true do end"))
    }

    @Test
    fun `a memory bomb is stopped by the memory ceiling`() {
        val g = lane().also { it.memoryceiling = 8L * 1024 * 1024; it.startmemorycount() }
        assertTrue("not enough memory" in luaError(g, "local t = {} for i = 1, 50000000 do t[i] = {i} end"))
    }

    @Test
    fun `seeded randomness repeats exactly`() {
        fun draws(): String {
            val g = lane(); g.seedrandom(7, 11)
            return run(g, "local s = '' for i = 1, 6 do s = s .. math.random(1, 1000) .. ',' end return s")
        }
        assertEquals(draws(), draws())
    }
}
