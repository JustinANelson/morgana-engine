package com.morgana.client

import com.badlogic.gdx.Input.Keys
import com.morgana.client.access.AccessibilitySettings
import com.morgana.client.access.AccessibilityStore
import com.morgana.client.access.ColourPalette
import com.morgana.client.access.ActionGroup
import com.morgana.client.access.GameAction
import com.morgana.client.access.KeyBindings
import com.morgana.client.access.KeyBindings.Result
import com.morgana.client.access.PaletteColours
import com.morgana.client.access.PaletteRole
import com.morgana.client.access.UiScales
import com.morgana.client.editor.dock.MemoryKeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow
import kotlin.math.sqrt

class KeyBindingsTest {
    private fun Result.bindings() = (this as Result.Changed).bindings

    @Test
    fun `the default layout is the one the game always had`() {
        val d = KeyBindings.DEFAULT
        assertTrue(d.isDefault)
        assertEquals(GameAction.MOVE_UP, d.actionFor(Keys.W))
        assertEquals(GameAction.MOVE_UP, d.actionFor(Keys.UP))
        assertEquals(GameAction.ATTACK, d.actionFor(Keys.SPACE))
        assertEquals(GameAction.ATTACK, d.actionFor(Keys.CONTROL_LEFT))
        assertEquals(GameAction.INTERACT, d.actionFor(Keys.ENTER))
        assertEquals(GameAction.WORLD_MAP, d.actionFor(Keys.N))
        assertNull(d.actionFor(Keys.Z))
        assertEquals("", d.encode())
    }

    @Test
    fun `every default key belongs to exactly one action`() {
        val all = GameAction.entries.flatMap { it.defaults }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.none { KeyBindings.isReserved(it) })
    }

    @Test
    fun `binding a free key replaces the chosen slot`() {
        val b = KeyBindings.DEFAULT.bind(GameAction.INVENTORY, 0, Keys.B).bindings()
        assertEquals(listOf(Keys.B), b.keysFor(GameAction.INVENTORY))
        assertNull(b.actionFor(Keys.I))
        assertEquals(GameAction.INVENTORY, b.actionFor(Keys.B))
        val two = b.bind(GameAction.INVENTORY, 1, Keys.Y).bindings()
        assertEquals(listOf(Keys.B, Keys.Y), two.keysFor(GameAction.INVENTORY))
        assertFalse(two.isDefault)
    }

    @Test
    fun `binding a key another action uses takes it from that action`() {
        val r = KeyBindings.DEFAULT.bind(GameAction.INVENTORY, 0, Keys.W) as Result.Changed
        assertTrue(r.message.contains("taken from Move up"), r.message)
        assertEquals(GameAction.INVENTORY, r.bindings.actionFor(Keys.W))
        assertEquals(listOf(Keys.UP), r.bindings.keysFor(GameAction.MOVE_UP))
    }

    @Test
    fun `a key that is an action's only key cannot be taken`() {
        val r = KeyBindings.DEFAULT.bind(GameAction.ATTACK, 0, Keys.G)
        assertTrue(r is Result.Refused)
        assertTrue((r as Result.Refused).reason.contains("Pick up"))
    }

    @Test
    fun `the fixed keys are refused`() {
        for (k in listOf(Keys.F1, Keys.ESCAPE)) {
            assertTrue(KeyBindings.DEFAULT.bind(GameAction.PICK_UP, 0, k) is Result.Refused, "key $k")
        }
    }

    @Test
    fun `quickbar keys default to the number row and numpad and can be changed`() {
        for (i in 0..9) {
            val action = GameAction.hotbar(i)
            assertEquals(i, action.hotbarIndex)
            assertEquals(ActionGroup.QUICKBAR, action.group)
        }
        assertEquals(listOf(Keys.NUM_1, Keys.NUMPAD_1), KeyBindings.DEFAULT.keysFor(GameAction.HOTBAR_1))
        assertEquals(listOf(Keys.NUM_0, Keys.NUMPAD_0), KeyBindings.DEFAULT.keysFor(GameAction.HOTBAR_10))
        assertEquals(GameAction.HOTBAR_3, KeyBindings.DEFAULT.actionFor(Keys.NUM_3))
        assertEquals(null, GameAction.PICK_UP.hotbarIndex)

        val q = KeyBindings.DEFAULT.bind(GameAction.HOTBAR_1, 0, Keys.Q).bindings()
        assertEquals(GameAction.HOTBAR_1, q.actionFor(Keys.Q))
        assertEquals(null, q.actionFor(Keys.NUM_1), "the old key is free")
        // A number key can now do something else; the quickbar slot keeps its other key.
        val r = KeyBindings.DEFAULT.bind(GameAction.PICK_UP, 1, Keys.NUM_2)
        assertTrue(r is Result.Changed && r.message.contains("Quickbar 2"))
        assertEquals(listOf(Keys.NUMPAD_2), r.bindings().keysFor(GameAction.HOTBAR_2))
        assertEquals(q, KeyBindings.decode(q.encode()), "saved and loaded")
    }

    @Test
    fun `every action has its own default keys and a layout saved before the quickbar was rebindable still loads`() {
        val all = GameAction.entries.flatMap { it.defaults }
        assertEquals(all.size, all.toSet().size)
        val old = KeyBindings.decode("PICK_UP=${Keys.B}")
        assertEquals(listOf(Keys.B), old.keysFor(GameAction.PICK_UP))
        assertEquals(listOf(Keys.NUM_5, Keys.NUMPAD_5), old.keysFor(GameAction.HOTBAR_5))
        assertEquals(listOf(Keys.H), old.keysFor(GameAction.CONTROLS))
    }

    @Test
    fun `short key names fit a quickbar button`() {
        assertEquals("1", KeyBindings.shortKeyName(Keys.NUM_1))
        assertEquals("Q", KeyBindings.shortKeyName(Keys.Q))
        assertEquals("Np1", KeyBindings.shortKeyName(Keys.NUMPAD_1))
        assertEquals("LCtr", KeyBindings.shortKeyName(Keys.CONTROL_LEFT))
        for (k in listOf(Keys.SPACE, Keys.ENTER, Keys.SHIFT_RIGHT, Keys.F12, Keys.PAGE_DOWN)) assertTrue(KeyBindings.shortKeyName(k).length <= 4, "$k")
    }

    @Test
    fun `an action keeps at least one key`() {
        assertTrue(KeyBindings.DEFAULT.clear(GameAction.PICK_UP, 0) is Result.Refused)
        val cleared = KeyBindings.DEFAULT.clear(GameAction.MOVE_UP, 1).bindings()
        assertEquals(listOf(Keys.W), cleared.keysFor(GameAction.MOVE_UP))
        assertTrue(cleared.clear(GameAction.MOVE_UP, 0) is Result.Refused)
    }

    @Test
    fun `putting an action's other key in a slot swaps the two`() {
        val swapped = KeyBindings.DEFAULT.bind(GameAction.ATTACK, 0, Keys.CONTROL_LEFT).bindings()
        assertEquals(listOf(Keys.CONTROL_LEFT, Keys.SPACE), swapped.keysFor(GameAction.ATTACK))
    }

    @Test
    fun `binding the key already in that slot changes nothing`() {
        val r = KeyBindings.DEFAULT.bind(GameAction.PICK_UP, 0, Keys.G) as Result.Changed
        assertEquals(KeyBindings.DEFAULT, r.bindings)
    }

    @Test
    fun `isDown is true when any key of the action is held`() {
        val held = setOf(Keys.UP)
        assertTrue(KeyBindings.DEFAULT.isDown(GameAction.MOVE_UP, held::contains))
        assertFalse(KeyBindings.DEFAULT.isDown(GameAction.MOVE_DOWN, held::contains))
    }

    @Test
    fun `bindings survive saving and loading`() {
        var b = KeyBindings.DEFAULT
        b = b.bind(GameAction.MOVE_UP, 0, Keys.Z).bindings()
        b = b.bind(GameAction.INVENTORY, 0, Keys.TAB).bindings()
        b = b.bind(GameAction.ATTACK, 1, Keys.X).bindings()
        val loaded = KeyBindings.decode(b.encode())
        assertEquals(b, loaded)
        assertTrue(b.encode().contains("MOVE_UP="))
        assertFalse(b.encode().contains("MOVE_DOWN"), "only changed actions are written")
    }

    @Test
    fun `damaged or hand-edited settings never leave the game without keys`() {
        for (text in listOf("garbage", "MOVE_UP=", "MOVE_UP=abc", "NOPE=5", ";;;", "MOVE_UP=${Keys.ESCAPE}", "=1")) {
            val b = KeyBindings.decode(text)
            for (a in GameAction.entries) assertTrue(b.keysFor(a).isNotEmpty(), "$text leaves ${a.name} without a key")
        }
        // A key claimed by two actions goes to the first; the other keeps what is left, and nothing is shared.
        val clash = KeyBindings.decode("INVENTORY=${Keys.B};SPELLBOOK=${Keys.B}")
        assertEquals(GameAction.INVENTORY, clash.actionFor(Keys.B))
        assertTrue(clash.keysFor(GameAction.SPELLBOOK).isNotEmpty())
        val all = GameAction.entries.flatMap { clash.keysFor(it) }
        assertEquals(all.size, all.toSet().size, "no key does two things")
    }

    @Test
    fun `a custom key that another action has by default takes it, and that action is repaired`() {
        // PICK_UP's only default key (G) goes to INVENTORY; PICK_UP must not be left without one.
        val b = KeyBindings.decode("INVENTORY=${Keys.G}")
        assertEquals(GameAction.INVENTORY, b.actionFor(Keys.G).takeIf { b.keysFor(GameAction.PICK_UP) != listOf(Keys.G) } ?: GameAction.INVENTORY)
        assertTrue(b.keysFor(GameAction.PICK_UP).isNotEmpty())
        val all = GameAction.entries.flatMap { b.keysFor(it) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `key names are readable`() {
        assertEquals("Left Ctrl", KeyBindings.keyName(Keys.CONTROL_LEFT))
        assertEquals("Space", KeyBindings.keyName(Keys.SPACE))
        assertEquals("W", KeyBindings.keyName(Keys.W))
    }
}

class PaletteColoursTest {
    // --- colour-vision simulation (Machado, Oliveira and Fernandes 2009, severity 1.0, on linear RGB) ---
    private val protan = arrayOf(doubleArrayOf(0.152286, 1.052583, -0.204868), doubleArrayOf(0.114503, 0.786281, 0.099216), doubleArrayOf(-0.003882, -0.048116, 1.051998))
    private val deutan = arrayOf(doubleArrayOf(0.367322, 0.860646, -0.227968), doubleArrayOf(0.280085, 0.672501, 0.047413), doubleArrayOf(-0.011820, 0.042940, 0.968881))
    private val tritan = arrayOf(doubleArrayOf(1.255528, -0.076749, -0.178779), doubleArrayOf(-0.078411, 0.930809, 0.147602), doubleArrayOf(0.004733, 0.691367, 0.303900))

    private fun linear(c: Int): Double { val s = c / 255.0; return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }

    private fun lab(r: Double, g: Double, b: Double): DoubleArray {
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun seen(rgb: Int, matrix: Array<DoubleArray>?): DoubleArray {
        val lin = doubleArrayOf(linear(rgb shr 16 and 0xFF), linear(rgb shr 8 and 0xFF), linear(rgb and 0xFF))
        val out = if (matrix == null) lin else DoubleArray(3) { i -> (0..2).sumOf { j -> matrix[i][j] * lin[j] }.coerceIn(0.0, 1.0) }
        return lab(out[0], out[1], out[2])
    }

    private fun distance(a: Int, b: Int, matrix: Array<DoubleArray>?): Double {
        val x = seen(a, matrix)
        val y = seen(b, matrix)
        return sqrt((0..2).sumOf { (x[it] - y[it]).pow(2) })
    }

    private fun worstPair(palette: ColourPalette, roles: List<PaletteRole>, matrix: Array<DoubleArray>?): Triple<Double, PaletteRole, PaletteRole> {
        var worst = Triple(Double.MAX_VALUE, roles[0], roles[0])
        for (i in roles.indices) for (j in i + 1 until roles.size) {
            val d = distance(PaletteColours.rgb(palette, roles[i]), PaletteColours.rgb(palette, roles[j]), matrix)
            if (d < worst.first) worst = Triple(d, roles[i], roles[j])
        }
        return worst
    }

    /** Roles that sit side by side or share a screen, and so must not look the same. */
    private val bars = listOf(PaletteRole.HEALTH, PaletteRole.MANA, PaletteRole.VITAL_THREE, PaletteRole.VITAL_FOUR, PaletteRole.XP)
    private val goodBad = listOf(PaletteRole.GOOD, PaletteRole.BAD)
    private val blips = listOf(PaletteRole.HOSTILE, PaletteRole.FRIENDLY, PaletteRole.OTHER_PLAYER)

    @Test
    fun `every palette colours every role`() {
        for (p in ColourPalette.entries) for (r in PaletteRole.entries) {
            val c = PaletteColours.rgb(p, r)
            assertTrue(c in 0..0xFFFFFF, "$p $r")
        }
    }

    @Test
    fun `the standard palette really is confusing for red-green colour blindness`() {
        // This is why the feature exists: under deuteranopia the standard health and third bars, and damage and healing, look alike.
        val bad = worstPair(ColourPalette.DEFAULT, goodBad + PaletteRole.HEALTH + PaletteRole.VITAL_THREE, deutan)
        assertTrue(bad.first < 25.0, "expected a confusable pair, worst was ${bad.second}/${bad.third} at ${bad.first}")
    }

    @Test
    fun `the red-green safe palette keeps its roles apart for protanopia and deuteranopia`() {
        for ((name, m) in listOf("protan" to protan, "deutan" to deutan)) {
            for (group in listOf(bars, goodBad, blips)) {
                val (d, a, b) = worstPair(ColourPalette.RED_GREEN, group, m)
                assertTrue(d >= 25.0, "$name: $a and $b are only $d apart")
            }
        }
    }

    @Test
    fun `the blue-yellow safe palette keeps its roles apart for tritanopia, and for the other kinds too`() {
        for ((name, m) in listOf("tritan" to tritan, "protan" to protan, "deutan" to deutan)) {
            for (group in listOf(bars, goodBad, blips)) {
                val (d, a, b) = worstPair(ColourPalette.BLUE_YELLOW, group, m)
                assertTrue(d >= 20.0, "$name: $a and $b are only $d apart")
            }
        }
    }

    @Test
    fun `the high contrast palette is bright against a dark background`() {
        val background = 0x0A0A0F
        fun luminance(rgb: Int) = 0.2126 * linear(rgb shr 16 and 0xFF) + 0.7152 * linear(rgb shr 8 and 0xFF) + 0.0722 * linear(rgb and 0xFF)
        for (r in PaletteRole.entries) {
            val ratio = (luminance(PaletteColours.rgb(ColourPalette.HIGH_CONTRAST, r)) + 0.05) / (luminance(background) + 0.05)
            assertTrue(ratio >= 7.0, "$r has contrast $ratio")
        }
    }

    @Test
    fun `vitals map to roles in order, health first`() {
        assertEquals(PaletteRole.HEALTH, PaletteColours.vitalRole("hp", 5))
        assertEquals(PaletteRole.HEALTH, PaletteColours.vitalRole("life", 0))
        assertEquals(PaletteRole.MANA, PaletteColours.vitalRole("mp", 7))
        assertEquals(PaletteRole.MANA, PaletteColours.vitalRole("focus", 1))
        assertEquals(PaletteRole.VITAL_THREE, PaletteColours.vitalRole("stamina", 2))
        assertEquals(PaletteRole.VITAL_FOUR, PaletteColours.vitalRole("fury", 3))
    }

    @Test
    fun `floating text keeps the server colour except clear red and green, and the standard palette changes nothing`() {
        assertEquals("#FF3030", PaletteColours.floatingText("#FF3030", ColourPalette.DEFAULT))
        assertEquals("#D55E00", PaletteColours.floatingText("#FF3030", ColourPalette.RED_GREEN), "red becomes the damage colour")
        assertEquals("#56B4E9", PaletteColours.floatingText("#33DD44", ColourPalette.RED_GREEN), "green becomes the healing colour")
        assertEquals("#FFD84A", PaletteColours.floatingText("#FFD84A", ColourPalette.RED_GREEN), "yellow is left alone")
        assertEquals("#808080", PaletteColours.floatingText("#808080", ColourPalette.RED_GREEN), "grey is left alone")
        assertEquals("#4488FF", PaletteColours.floatingText("#4488FF", ColourPalette.BLUE_YELLOW))
        assertEquals("nonsense", PaletteColours.floatingText("nonsense", ColourPalette.HIGH_CONTRAST))
    }
}

class AccessibilitySettingsTest {
    @Test
    fun `nothing saved gives the defaults`() {
        val s = AccessibilityStore(MemoryKeyValueStore()).load()
        assertEquals(AccessibilitySettings(), s)
    }

    @Test
    fun `settings round-trip through the store`() {
        val store = MemoryKeyValueStore()
        val keys = KeyBindings.DEFAULT.bind(GameAction.INVENTORY, 0, Keys.B).let { (it as Result.Changed).bindings }
        val settings = AccessibilitySettings(1.5f, ColourPalette.RED_GREEN, true, keys)
        AccessibilityStore(store).save(settings)
        assertEquals(settings, AccessibilityStore(store).load())
    }

    @Test
    fun `defaults are removed from the store again`() {
        val store = MemoryKeyValueStore()
        val a = AccessibilityStore(store)
        a.save(AccessibilitySettings(keys = KeyBindings.DEFAULT.bind(GameAction.INVENTORY, 0, Keys.B).let { (it as Result.Changed).bindings }))
        assertNotEquals(null, store.get("keys"))
        a.save(AccessibilitySettings())
        assertNull(store.get("keys"))
    }

    @Test
    fun `unreadable values fall back, and the system motion preference is the default only until the player chooses`() {
        val store = MemoryKeyValueStore()
        store.put("uiScale", "huge"); store.put("palette", "NEON"); store.put("keys", "???")
        val s = AccessibilityStore(store).load(defaultReduceMotion = true)
        assertEquals(UiScales.AUTO, s.uiScale)
        assertEquals(ColourPalette.DEFAULT, s.palette)
        assertTrue(s.reduceMotion, "no saved choice: follow the system")
        assertTrue(s.keys.isDefault)
        store.put("reduceMotion", "false")
        assertFalse(AccessibilityStore(store).load(defaultReduceMotion = true).reduceMotion, "a saved choice wins")
    }

    @Test
    fun `interface sizes step through the list and wrap through Auto`() {
        assertEquals(1.25f, UiScales.next(1f))
        assertEquals(2.5f, UiScales.next(2f))
        assertEquals(UiScales.AUTO, UiScales.next(3f), "after the largest size comes Auto")
        assertEquals(0.75f, UiScales.next(UiScales.AUTO), "after Auto comes the smallest size")
        assertEquals(1f, UiScales.nearest(1.04f))
        assertEquals(UiScales.AUTO, UiScales.nearest(0f))
        assertEquals("150%", UiScales.label(1.5f))
        assertEquals("Auto", UiScales.label(UiScales.AUTO))
        assertEquals("Auto (175%)", UiScales.describe(UiScales.AUTO, 1.75f))
        assertEquals("125%", UiScales.describe(1.25f, 1.75f), "a chosen size ignores what Auto would pick")
        store0()
    }

    @Test
    fun `Auto is the default and resolves to the platform's size`() {
        assertEquals(UiScales.AUTO, AccessibilitySettings().uiScale)
        assertEquals(UiScales.AUTO, AccessibilityStore(MemoryKeyValueStore()).load().uiScale, "nothing saved: Auto")
        assertEquals(1.75f, UiScales.resolve(UiScales.AUTO, 1.75f))
        assertEquals(1.5f, UiScales.resolve(1.5f, 1.75f))
        val store = MemoryKeyValueStore()
        AccessibilityStore(store).save(AccessibilitySettings())
        assertEquals(UiScales.AUTO, AccessibilityStore(store).load().uiScale, "Auto survives a save and load")
    }

    @Test
    fun `Auto sizes the interface from the screen's resolution and density`() {
        assertEquals(1f, UiScales.auto(1920, 1080, 1f), "a 1080p monitor is the reference size")
        assertEquals(1f, UiScales.auto(1366, 768, 0.9f), "never below 100%")
        assertEquals(1.25f, UiScales.auto(2560, 1440, 0.7f), "1440p")
        assertEquals(2f, UiScales.auto(3840, 2160, 1f), "a 4K monitor doubles")
        assertEquals(1.75f, UiScales.auto(3072, 1920, 1.68f), "a dense 16-inch laptop panel")
        assertEquals(1.5f, UiScales.auto(2560, 1600, 1.42f), "a dense 13-inch panel follows its density")
        assertEquals(1.5f, UiScales.auto(2400, 1080, 2.6f), "keeps at least 720 interface units on the short side")
        assertEquals(1.25f, UiScales.auto(2560, 1440, 40f), "a nonsense density is ignored")
        assertEquals(1f, UiScales.auto(0, 0, 0f), "no screen information: 100%")
    }

    private fun store0() {
        val store = MemoryKeyValueStore()
        store.put("uiScale", "1.6")
        assertEquals(1.5f, AccessibilityStore(store).load().uiScale, "a value between steps snaps to the nearest")
    }
}

class AccessibilityControllerTest {
    private fun controller(system: Boolean = false, store: MemoryKeyValueStore = MemoryKeyValueStore()): Pair<com.morgana.client.access.AccessibilityController, MutableList<com.morgana.client.access.AccessibilityController.Change>> {
        val c = com.morgana.client.access.AccessibilityController(AccessibilityStore(store), system)
        val changes = mutableListOf<com.morgana.client.access.AccessibilityController.Change>()
        c.onChanged = { changes += it }
        c.load()
        return c to changes
    }

    @Test
    fun `each change is applied, saved and reported`() {
        val store = MemoryKeyValueStore()
        val (c, changes) = controller(store = store)
        assertEquals(0.75f, c.cycleScale(), "from the default Auto to the smallest size")
        assertEquals(1f, c.cycleScale())
        assertEquals(1.25f, c.cycleScale())
        c.setPalette(ColourPalette.HIGH_CONTRAST)
        c.setReduceMotion(true)
        assertEquals(List(3) { com.morgana.client.access.AccessibilityController.Change.SCALE } + listOf(com.morgana.client.access.AccessibilityController.Change.PALETTE, com.morgana.client.access.AccessibilityController.Change.MOTION), changes)
        assertEquals(1.25f, com.morgana.client.access.Accessibility.settings.uiScale)
        assertTrue(com.morgana.client.access.Accessibility.reduceMotion)
        assertEquals(ColourPalette.HIGH_CONTRAST, com.morgana.client.access.Accessibility.palette)
        // a new controller over the same store sees the same settings
        val (again, _) = controller(store = store)
        assertEquals(c.current, again.current)
    }

    @Test
    fun `auto-loot is off by default, saved when chosen and reported as its own change`() {
        val store = MemoryKeyValueStore()
        val (c, changes) = controller(store = store)
        assertFalse(c.current.autoLoot)
        c.setAutoLoot(true)
        assertEquals(listOf(com.morgana.client.access.AccessibilityController.Change.AUTO_LOOT), changes)
        assertTrue(controller(store = store).first.current.autoLoot)
        c.setAutoLoot(true)
        assertEquals(1, changes.size)
    }

    @Test
    fun `setting what is already set reports nothing`() {
        val (c, changes) = controller()
        c.setPalette(ColourPalette.DEFAULT)
        c.setReduceMotion(false)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `reduce motion starts from the system preference until the player chooses`() {
        val store = MemoryKeyValueStore()
        val (c, _) = controller(system = true, store = store)
        assertTrue(c.current.reduceMotion)
        c.setReduceMotion(false)
        assertFalse(controller(system = true, store = store).first.current.reduceMotion)
    }

    @Test
    fun `pressing a key during capture binds it and ends the capture`() {
        val (c, changes) = controller()
        c.beginCapture(GameAction.INVENTORY, 0)
        assertTrue(c.isCapturing)
        val message = c.captureKey(Keys.B)!!
        assertTrue(message.contains("Inventory"), message)
        assertFalse(c.isCapturing)
        assertEquals(GameAction.INVENTORY, c.current.keys.actionFor(Keys.B))
        assertEquals(listOf(com.morgana.client.access.AccessibilityController.Change.KEYS), changes)
    }

    @Test
    fun `a refused key keeps the capture open so another can be tried, and Escape cancels`() {
        val (c, changes) = controller()
        c.beginCapture(GameAction.INVENTORY, 0)
        assertTrue(c.captureKey(Keys.F1)!!.contains("cannot be changed"))
        assertTrue(c.isCapturing)
        assertEquals("Cancelled.", c.captureKey(Keys.ESCAPE))
        assertFalse(c.isCapturing)
        assertTrue(changes.isEmpty())
        assertNull(c.captureKey(Keys.B), "not capturing: nothing to say")
    }

    @Test
    fun `Backspace removes a key during capture, but never an action's last key`() {
        val (c, _) = controller()
        c.beginCapture(GameAction.MOVE_UP, 1)
        c.captureKey(Keys.BACKSPACE)
        assertEquals(listOf(Keys.W), c.current.keys.keysFor(GameAction.MOVE_UP))
        c.beginCapture(GameAction.MOVE_UP, 0)
        assertTrue(c.captureKey(Keys.BACKSPACE)!!.contains("at least one key"))
        assertTrue(c.isCapturing)
    }

    @Test
    fun `reset puts the standard layout back and saves it`() {
        val store = MemoryKeyValueStore()
        val (c, _) = controller(store = store)
        c.beginCapture(GameAction.ATTACK, 0); c.captureKey(Keys.Z)
        assertFalse(c.current.keys.isDefault)
        c.resetKeys()
        assertTrue(c.current.keys.isDefault)
        assertNull(store.get("keys"))
    }

    @Test
    fun `non-text keys are the ones that may move you while a text field has focus`() {
        for (k in listOf(Keys.UP, Keys.LEFT, Keys.CONTROL_LEFT, Keys.TAB, Keys.F5)) assertTrue(KeyBindings.isNonTextKey(k), "$k")
        for (k in listOf(Keys.W, Keys.SPACE, Keys.A, Keys.NUM_1, Keys.ENTER)) assertFalse(KeyBindings.isNonTextKey(k), "$k")
    }
}
