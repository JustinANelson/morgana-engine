package com.morgana.client

import com.badlogic.gdx.audio.Sound
import com.morgana.client.audio.RetroSoundEngine
import com.morgana.client.audio.SoundLoader
import com.morgana.client.ui.joystickDirection
import com.morgana.client.ui.shouldShowTouchControls
import com.morgana.domain.spatial.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class TouchAndAudioSettingsTest {

    @Test
    fun `joystick ignores the dead zone and picks the dominant axis`() {
        assertNull(joystickDirection(3f, -2f, deadZone = 10f))
        assertEquals(Direction.RIGHT, joystickDirection(30f, 10f, 10f))
        assertEquals(Direction.LEFT, joystickDirection(-30f, 10f, 10f))
        assertEquals(Direction.UP, joystickDirection(5f, 40f, 10f))
        assertEquals(Direction.DOWN, joystickDirection(-5f, -40f, 10f))
        assertEquals(Direction.UP_RIGHT, joystickDirection(30f, 30f, 10f))
        assertEquals(Direction.UP_LEFT, joystickDirection(-30f, 30f, 10f))
        assertEquals(Direction.DOWN_RIGHT, joystickDirection(30f, -30f, 10f))
        assertEquals(Direction.DOWN_LEFT, joystickDirection(-30f, -30f, 10f))
        assertEquals(Direction.RIGHT, joystickDirection(30f, 25f, 10f, allowDiagonals = false))
        assertEquals(Direction.UP, joystickDirection(-25f, 30f, 10f, allowDiagonals = false))
    }

    @Test
    fun `touch controls never cover the map while it is being edited`() {
        assertEquals(true, shouldShowTouchControls(loggedIn = true, enabledInSettings = true, mapEditorActive = false))
        assertEquals(false, shouldShowTouchControls(loggedIn = true, enabledInSettings = true, mapEditorActive = true))
        assertEquals(false, shouldShowTouchControls(loggedIn = false, enabledInSettings = true, mapEditorActive = false))
        assertEquals(false, shouldShowTouchControls(loggedIn = true, enabledInSettings = false, mapEditorActive = false))
    }

    private fun engineWithRecorder(played: MutableList<Float>, looped: MutableList<Float>, volumes: MutableList<Float>): RetroSoundEngine {
        val sound = Proxy.newProxyInstance(Sound::class.java.classLoader, arrayOf(Sound::class.java)) { _, m, args ->
            when (m.name) {
                "play" -> if (args?.size == 1) played.add(args[0] as Float)
                "loop" -> if (args?.size == 1) { looped.add(args[0] as Float); return@newProxyInstance 7L }
                "setVolume" -> volumes.add(args!![1] as Float)
            }
            if (m.returnType == java.lang.Long.TYPE) 0L else null
        } as Sound
        return RetroSoundEngine(loader = object : SoundLoader {
            override fun load(sfx: RetroSoundEngine.Sfx, wav: ByteArray) = sound
            override fun loadTrack(trackName: String, wav: ByteArray) = sound
        }).also { it.loadAll() }
    }

    @Test
    fun `effects and music are controlled independently`() {
        val played = mutableListOf<Float>(); val looped = mutableListOf<Float>(); val volumes = mutableListOf<Float>()
        val engine = engineWithRecorder(played, looped, volumes)

        engine.sfxMuted = true
        engine.play(RetroSoundEngine.Sfx.HIT)
        assertEquals(0, played.size)
        engine.sfxMuted = false
        engine.sfxVolume = 0.5f
        engine.play(RetroSoundEngine.Sfx.HIT)
        assertEquals(0.8f * 0.5f, played.single(), 0.001f)

        engine.playBgm("town_theme")
        assertEquals(0.8f * 0.4f, looped.single(), 0.001f)
        engine.musicMuted = true
        assertEquals(0f, volumes.last(), 0f)
        engine.musicMuted = false
        engine.bgmVolume = 1f
        assertEquals(0.8f, volumes.last(), 0.001f)
        // Muting music must not silence effects.
        engine.musicMuted = true
        engine.play(RetroSoundEngine.Sfx.CLICK)
        assertEquals(2, played.size)
    }

    @Test
    fun `touch controls keep top-right hotkey below compact HUD Menu button and hide buttons when visible is false`() {
        val showAtk = java.util.concurrent.atomic.AtomicBoolean(true)
        val showSpl = java.util.concurrent.atomic.AtomicBoolean(true)
        val controls = com.morgana.client.ui.TouchControls(
            font = { error("font not needed for layout test") },
            faceButtons = listOf(
                com.morgana.client.ui.TouchFaceButton({ "Atk" }, com.badlogic.gdx.graphics.Color.RED, visible = { showAtk.get() }) {},
                com.morgana.client.ui.TouchFaceButton({ "Use" }, com.badlogic.gdx.graphics.Color.BLUE) {},
                com.morgana.client.ui.TouchFaceButton("Get", com.badlogic.gdx.graphics.Color.GOLD) {},
                com.morgana.client.ui.TouchFaceButton({ "Spell" }, com.badlogic.gdx.graphics.Color.PURPLE, visible = { showSpl.get() }) {}
            ),
            hotkeys = listOf(
                com.morgana.client.ui.TouchHotkey({ "1" }, visible = { showSpl.get() }) {},
                com.morgana.client.ui.TouchHotkey({ "Inv" }) {}
            )
        )
        controls.layoutFor(800f, 480f)
        // Compact HUD Menu button is at y = 480 - 42 - 8 = 430 (bottom edge at 430); topHotkeyMaxY must sit below 430.
        org.junit.jupiter.api.Assertions.assertTrue(controls.topHotkeyMaxY()!! <= 480f - 58f + 0.01f)
        assertEquals(4, controls.visibleFaceCount())
        assertEquals(2, controls.visibleHotkeyCount())

        showAtk.set(false)
        showSpl.set(false)
        controls.layoutFor(800f, 480f)
        assertEquals(2, controls.visibleFaceCount())
        assertEquals(1, controls.visibleHotkeyCount())
    }

    @Test
    fun `world state UI profile hides combat, spells, and empty hotbar slots in peaceful realms`() {
        val state = com.morgana.client.state.ClientWorldState()
        val peacefulDef = com.morgana.domain.game.MorganaClassic.definition.copy(
            combat = com.morgana.domain.game.MorganaClassic.definition.combat.copy(module = "noop"),
            ui = com.morgana.domain.game.UiProfile(combat = false, spells = false)
        )
        state.gameDefinition = peacefulDef
        org.junit.jupiter.api.Assertions.assertFalse(state.showCombatUi())
        org.junit.jupiter.api.Assertions.assertFalse(state.showSpellsUi())
        org.junit.jupiter.api.Assertions.assertFalse(state.showTouchSpellButton())
        org.junit.jupiter.api.Assertions.assertFalse(state.showHotbarSlot(0), "empty hotbar slot is hidden in peaceful realm")
        org.junit.jupiter.api.Assertions.assertFalse(state.showAnyHotbarSlot())

        state.hotbarSlots[0] = com.morgana.client.state.HotbarSlotAction.UseItem(1, "Turnip Seed", 1)
        org.junit.jupiter.api.Assertions.assertTrue(state.showHotbarSlot(0), "populated hotbar slot is shown")
        org.junit.jupiter.api.Assertions.assertTrue(state.showAnyHotbarSlot())
    }

    @Test
    fun `directional jump landing resolves furthest unblocked tile and checks stamina`() {
        val state = com.morgana.client.state.ClientWorldState()
        val map = com.morgana.domain.map.MapDefinition(
            id = 1,
            name = "Field",
            width = 20,
            height = 20,
            cells = List(20 * 20) { idx ->
                if (idx == 10 * 20 + 15) com.morgana.domain.map.MapCell(attribute = com.morgana.domain.map.TileAttribute.Blocked)
                else com.morgana.domain.map.MapCell()
            }
        )
        state.activeMap = map
        state.gameDefinition = com.morgana.domain.game.MorganaClassic.definition.copy(
            jump = com.morgana.domain.game.JumpDefinition(enabled = true, maxRangeTiles = 5, staminaCost = 15, staminaVitalId = "stamina")
        )
        state.localCharacter = com.morgana.domain.entity.CharacterState(
            id = "c1",
            accountId = "a1",
            name = "Hero",
            classId = 1,
            position = com.morgana.domain.spatial.WorldPosition.fromTile(mapId = 1, tileX = 10, tileY = 10, direction = com.morgana.domain.spatial.Direction.RIGHT),
            vitals = mapOf("stamina" to com.morgana.domain.stats.VitalState(cur = 20, max = 100))
        )

        // (15, 10) is blocked, so furthest unblocked step along RIGHT within 5 tiles is (14, 10)
        assertEquals(com.morgana.domain.spatial.TileCoord(14, 10), state.findDirectionalJumpLanding())
        org.junit.jupiter.api.Assertions.assertTrue(state.canAffordJump())

        // Diagonal facing works too
        state.localCharacter = state.localCharacter!!.copy(
            position = state.localCharacter!!.position.copy(direction = com.morgana.domain.spatial.Direction.UP_RIGHT),
            vitals = mapOf("stamina" to com.morgana.domain.stats.VitalState(cur = 10, max = 100))
        )
        assertEquals(com.morgana.domain.spatial.TileCoord(15, 5), state.findDirectionalJumpLanding())
        org.junit.jupiter.api.Assertions.assertFalse(state.canAffordJump())

        // A free jump (cost 0) is allowed at any stamina
        state.gameDefinition = state.gameDefinition!!.copy(jump = state.gameDefinition!!.jump!!.copy(staminaCost = 0))
        state.localCharacter = state.localCharacter!!.copy(vitals = emptyMap())
        org.junit.jupiter.api.Assertions.assertTrue(state.canAffordJump())
    }

    @Test
    fun `touch controls lay out all 10 quickbar hotkeys plus Inv and Spells inside mobile viewports`() {
        val controls = com.morgana.client.ui.TouchControls(
            font = { error("font not needed for layout test") },
            faceButtons = listOf(
                com.morgana.client.ui.TouchFaceButton("Atk", com.badlogic.gdx.graphics.Color.RED) {},
                com.morgana.client.ui.TouchFaceButton("Use", com.badlogic.gdx.graphics.Color.BLUE) {},
                com.morgana.client.ui.TouchFaceButton("Get", com.badlogic.gdx.graphics.Color.GOLD) {},
                com.morgana.client.ui.TouchFaceButton("Spl", com.badlogic.gdx.graphics.Color.PURPLE) {}
            ),
            hotkeys = (1..10).map { n -> com.morgana.client.ui.TouchHotkey({ "$n" }) {} } + listOf(
                com.morgana.client.ui.TouchHotkey({ "Inv" }) {},
                com.morgana.client.ui.TouchHotkey({ "Spl" }) {}
            )
        )
        for ((w, h) in listOf(800f to 480f, 720f to 360f)) {
            controls.layoutFor(w, h)
            assertEquals(12, controls.visibleHotkeyCount())
            org.junit.jupiter.api.Assertions.assertTrue(controls.topHotkeyMaxY()!! <= h - 58f + 0.01f, "top overflow on ${w}x$h")
            org.junit.jupiter.api.Assertions.assertTrue(controls.bottomHotkeyMinY()!! >= 14f - 0.01f, "bottom overflow on ${w}x$h")
        }
    }
}
