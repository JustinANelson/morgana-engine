package com.morgana.client

import com.badlogic.gdx.audio.Sound
import com.morgana.client.audio.RetroSoundEngine
import com.morgana.client.audio.SoundLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/** The sound engine no longer assumes temp files, so each platform (desktop, Android, browser) supplies its own loader. */
class SoundLoaderTest {

    private fun recordingSound(
        played: MutableList<Float> = mutableListOf(),
        looped: MutableList<Float> = mutableListOf(),
        stopped: MutableList<Long?> = mutableListOf()
    ): Sound =
        Proxy.newProxyInstance(Sound::class.java.classLoader, arrayOf(Sound::class.java)) { _, method, args ->
            when (method.name) {
                "play" -> if (args?.size == 1) played.add(args[0] as Float)
                "loop" -> if (args?.size == 1) {
                    val vol = args[0] as Float
                    looped.add(vol)
                    return@newProxyInstance 101L
                }
                "stop" -> stopped.add(if (args?.isNotEmpty() == true) args[0] as Long else null)
            }
            if (method.returnType == java.lang.Long.TYPE) 0L else null
        } as Sound

    @Test
    fun `every effect is generated and handed to the platform loader as WAV bytes`() {
        val seen = mutableListOf<Pair<RetroSoundEngine.Sfx, ByteArray>>()
        val engine = RetroSoundEngine(loader = SoundLoader { sfx, wav -> seen.add(sfx to wav); null })
        engine.loadAll()
        assertEquals(RetroSoundEngine.Sfx.entries, seen.map { it.first })
        assertTrue(seen.all { (_, wav) -> String(wav.copyOfRange(0, 4)) == "RIFF" && String(wav.copyOfRange(8, 12)) == "WAVE" })
    }

    @Test
    fun `every bgm track is generated and handed to the platform loader as WAV bytes`() {
        val seenTracks = mutableListOf<Pair<String, ByteArray>>()
        val customLoader = object : SoundLoader {
            override fun load(sfx: RetroSoundEngine.Sfx, wav: ByteArray): Sound? = null
            override fun loadTrack(trackName: String, wav: ByteArray): Sound? {
                seenTracks.add(trackName to wav)
                return null
            }
        }
        val engine = RetroSoundEngine(loader = customLoader)
        engine.loadAll()
        assertEquals(RetroSoundEngine.BgmTrack.entries.map { it.trackId }, seenTracks.map { it.first })
        assertTrue(seenTracks.all { (_, wav) -> String(wav.copyOfRange(0, 4)) == "RIFF" && String(wav.copyOfRange(8, 12)) == "WAVE" })
    }

    @Test
    fun `bgm plays loops stops and handles volume adjustments`() {
        val looped = mutableListOf<Float>()
        val stopped = mutableListOf<Long?>()
        val customLoader = object : SoundLoader {
            override fun load(sfx: RetroSoundEngine.Sfx, wav: ByteArray): Sound? = null
            override fun loadTrack(trackName: String, wav: ByteArray): Sound? =
                recordingSound(looped = looped, stopped = stopped)
        }
        val engine = RetroSoundEngine(masterVolume = 0.5f, loader = customLoader)
        engine.bgmVolume = 0.5f
        engine.loadAll()
        assertEquals(RetroSoundEngine.BgmTrack.entries.size, engine.bgmLoadedCount)

        engine.playBgm("town_theme")
        assertEquals(RetroSoundEngine.BgmTrack.TOWN_THEME, engine.currentBgmTrack)
        assertEquals(listOf(0.25f), looped) // 0.5 master * 0.5 bgm

        // Switch track: the new music starts silent and the old keeps playing until the crossfade finishes.
        engine.playBgm("dungeon_theme")
        assertEquals(RetroSoundEngine.BgmTrack.DUNGEON_THEME, engine.currentBgmTrack)
        assertEquals(listOf(0.25f, 0f), looped)
        assertEquals(0, stopped.size)
        engine.update(RetroSoundEngine.BGM_FADE_SECONDS + 0.1f)
        assertEquals(1, stopped.size)

        engine.stopBgm()
        org.junit.jupiter.api.Assertions.assertNull(engine.currentBgmTrack)
        assertEquals(2, stopped.size)
    }

    @Test
    fun `loaded effects play at master volume and a failing loader leaves the engine silent but working`() {
        val played = mutableListOf<Float>()
        val engine = RetroSoundEngine(masterVolume = 0.5f, loader = SoundLoader { _, _ -> recordingSound(played) })
        engine.loadAll()
        assertEquals(RetroSoundEngine.Sfx.entries.size, engine.loadedCount)
        engine.play(RetroSoundEngine.Sfx.HIT)
        assertEquals(listOf(0.5f), played)

        val silent = RetroSoundEngine(loader = SoundLoader { _, _ -> error("no audio device") })
        silent.loadAll()
        assertEquals(0, silent.loadedCount)
        silent.play(RetroSoundEngine.Sfx.HIT) // must not throw
    }

    @Test
    fun `lazy bgm synthesis and frame-by-frame sfx warmup work without upfront loadAll`() {
        val played = mutableListOf<Float>()
        val looped = mutableListOf<Float>()
        val seenTracks = mutableListOf<String>()
        val loader = object : SoundLoader {
            override fun load(sfx: RetroSoundEngine.Sfx, wav: ByteArray): Sound = recordingSound(played = played)
            override fun loadTrack(trackName: String, wav: ByteArray): Sound {
                seenTracks += trackName
                return recordingSound(looped = looped)
            }
        }
        val engine = RetroSoundEngine(masterVolume = 1f, loader = loader)
        assertEquals(0, engine.loadedCount, "no upfront SFX synthesis before update or play")
        assertEquals(0, engine.bgmLoadedCount, "no upfront BGM synthesis before playBgm")

        // Playing an SFX before warmup loads just that one effect on demand.
        engine.play(RetroSoundEngine.Sfx.CLICK)
        assertEquals(1, engine.loadedCount)
        assertEquals(1, played.size)

        // Playing a BGM track synthesizes only that requested track on demand.
        engine.playBgm("town_theme")
        assertEquals(listOf("town_theme"), seenTracks)
        assertEquals(1, engine.bgmLoadedCount)

        // Subsequent frame updates warm up remaining SFX one per frame.
        repeat(RetroSoundEngine.Sfx.entries.size) { engine.update(0.016f) }
        assertEquals(RetroSoundEngine.Sfx.entries.size, engine.loadedCount)
    }

    @Test
    fun `a realm-shipped track overrides the built-in theme loops and is forgotten on clear`() {
        val looped = mutableListOf<Float>()
        val stopped = mutableListOf<Long?>()
        val encoded = mutableListOf<Triple<String, Int, String>>()
        val customLoader = object : SoundLoader {
            override fun load(sfx: RetroSoundEngine.Sfx, wav: ByteArray): Sound? = null
            override fun loadTrack(trackName: String, wav: ByteArray): Sound? = recordingSound(looped = looped, stopped = stopped)
            override fun loadEncodedTrack(trackName: String, bytes: ByteArray, extension: String): Sound? {
                encoded.add(Triple(trackName, bytes.size, extension))
                return recordingSound(looped = looped, stopped = stopped)
            }
        }
        val engine = RetroSoundEngine(masterVolume = 1f, loader = customLoader)
        engine.bgmVolume = 0.5f
        engine.registerCustomTrack("Grass", byteArrayOf(1, 2, 3))

        engine.playBgm("grass")
        assertEquals("grass", engine.currentCustomTrackId)
        assertEquals(listOf(Triple("grass", 3, "ogg")), encoded)
        assertEquals(listOf(0.5f), looped)
        engine.playBgm("grass") // already playing: no second voice
        assertEquals(1, looped.size)

        engine.playBgm("town_theme") // unregistered ids still use the built-in themes
        assertEquals(RetroSoundEngine.BgmTrack.TOWN_THEME, engine.currentBgmTrack)
        assertEquals(null, engine.currentCustomTrackId)

        engine.playBgm("grass")
        engine.clearCustomTracks()
        assertEquals(null, engine.currentCustomTrackId)
        engine.playBgm("grass") // no longer registered and not built in: silence
        assertEquals(null, engine.currentCustomTrackId)
    }
}
