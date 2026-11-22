package com.morgana.client.assets

import com.morgana.client.state.ClientWorldState
import com.morgana.domain.content.AnimationDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimationStripLayoutTest {

    @Test
    fun `computeStripFrames slices horizontal square strips and falls back cleanly`() {
        val five = AnimationStripLayout.computeStripFrames(sheetWidth = 160, sheetHeight = 32)
        assertEquals(5, five.size)
        assertEquals(AnimationFrameRect(0, 0, 32, 32), five[0])
        assertEquals(AnimationFrameRect(64, 0, 32, 32), five[2])
        assertEquals(AnimationFrameRect(128, 0, 32, 32), five[4])

        val single = AnimationStripLayout.computeStripFrames(sheetWidth = 48, sheetHeight = 48)
        assertEquals(1, single.size)
        assertEquals(AnimationFrameRect(0, 0, 48, 48), single[0])

        val irregular = AnimationStripLayout.computeStripFrames(sheetWidth = 50, sheetHeight = 32)
        assertEquals(1, irregular.size)
        assertEquals(AnimationFrameRect(0, 0, 50, 32), irregular[0])
    }

    @Test
    fun `resolveFrameIndex steps monotonically through duration and supports looping`() {
        assertEquals(0, AnimationStripLayout.resolveFrameIndex(0.0f, frameDurationMs = 80, definitionFrames = 5, availableFrames = 5))
        assertEquals(1, AnimationStripLayout.resolveFrameIndex(0.09f, frameDurationMs = 80, definitionFrames = 5, availableFrames = 5))
        assertEquals(4, AnimationStripLayout.resolveFrameIndex(0.35f, frameDurationMs = 80, definitionFrames = 5, availableFrames = 5))
        assertEquals(4, AnimationStripLayout.resolveFrameIndex(2.0f, frameDurationMs = 80, definitionFrames = 5, availableFrames = 5))

        // Looping wraps across available frames
        assertEquals(0, AnimationStripLayout.resolveFrameIndex(0.41f, frameDurationMs = 80, definitionFrames = 5, availableFrames = 5, loop = true))
        assertEquals(2, AnimationStripLayout.resolveFrameIndex(0.57f, frameDurationMs = 80, definitionFrames = 5, availableFrames = 5, loop = true))

        // Definition requesting more frames than strip repeats across the strip cleanly
        assertEquals(1, AnimationStripLayout.resolveFrameIndex(0.49f, frameDurationMs = 80, definitionFrames = 8, availableFrames = 5))
        // Legacy single-frame PNG always resolves frame 0
        assertEquals(0, AnimationStripLayout.resolveFrameIndex(0.25f, frameDurationMs = 80, definitionFrames = 5, availableFrames = 1))
    }

    @Test
    fun `TriggerAnimation resolves sheet, timing, and layer order from AnimationDefinition catalog`() {
        val state = ClientWorldState()
        val map = MapDefinition(id = 1, name = "Arena", width = 10, height = 10)
        state.applyServerPacket(
            ServerPacket.ContentCatalogUpdated(
                WorldContentCatalogDto(
                    animations = listOf(
                        AnimationDefinition(
                            id = 9,
                            name = "Ground Sigil",
                            spriteSheetId = 2,
                            frameCount = 6,
                            frameDurationMs = 120,
                            renderAboveEntities = false
                        )
                    )
                )
            )
        )
        state.applyServerPacket(ServerPacket.MapStateSnapshot(activeMap = map))
        state.applyServerPacket(ServerPacket.TriggerAnimation(mapId = 1, tileX = 4, tileY = 5, animationId = 9))

        assertEquals(1, state.activeAnimations.size)
        val anim = state.activeAnimations.single()
        assertEquals(2, anim.spriteSheetId)
        assertEquals(6, anim.frameCount)
        assertEquals(120, anim.frameDurationMs)
        assertFalse(anim.renderAboveEntities)
        assertEquals(0.72f, anim.totalDurationSeconds, 0.001f)

        state.updateVisualTimers(0.50f)
        assertEquals(1, state.activeAnimations.size)
        state.updateVisualTimers(0.25f)
        assertTrue(state.activeAnimations.isEmpty())
    }
}
