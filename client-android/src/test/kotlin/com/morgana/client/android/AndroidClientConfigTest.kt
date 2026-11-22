package com.morgana.client.android

import com.morgana.client.state.ClientWorldState
import com.morgana.domain.map.MapDefinition
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AndroidClientConfigTest {

    @Test
    fun `android launcher defaults to emulator host alias and shares ClientWorldState with client-core`() {
        assertEquals("10.0.2.2", AndroidLauncher.DEFAULT_EMULATOR_HOST)
        assertEquals(7001, AndroidLauncher.DEFAULT_SERVER_PORT)

        val state = ClientWorldState()
        val map = MapDefinition(id = 1, name = "Havenwood Mobile", width = 12, height = 10)
        state.applyServerPacket(ServerPacket.MapStateSnapshot(activeMap = map))
        assertEquals("Havenwood Mobile", state.activeMap?.name)
    }
}

