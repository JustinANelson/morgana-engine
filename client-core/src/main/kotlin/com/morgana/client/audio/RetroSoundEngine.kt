package com.morgana.client.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Sound
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Procedural retro 8-bit/16-bit sound effect engine for Morgana ORPG.
 * Generates classic chiptune PCM audio in memory and loads them into LibGDX [Sound] instances
 * without requiring external sound files.
 */
class RetroSoundEngine(
    var masterVolume: Float = 0.8f,
    var muted: Boolean = false,
    /** Turns synthesized WAV bytes into a playable [Sound]; the browser cannot use temp files, so it supplies its own. */
    private val loader: SoundLoader = TempFileSoundLoader()
) {
    enum class Sfx {
        SWING,
        HIT,
        CRIT,
        SPELL,
        HEAL,
        LEVEL_UP,
        PICKUP,
        WARP,
        CLICK,
        METEOR_DROP,
        DRAGON_BALL_DROP
    }

    enum class BgmTrack(val trackId: String) {
        TOWN_THEME("town_theme"),
        FIELD_THEME("field_theme"),
        MARSH_THEME("marsh_theme"),
        DUNGEON_THEME("dungeon_theme");

        companion object {
            fun fromId(id: String): BgmTrack? =
                entries.firstOrNull {
                    it.trackId.equals(id, ignoreCase = true) ||
                    it.name.equals(id, ignoreCase = true) ||
                    id.equals("bgm_${it.trackId}", ignoreCase = true) ||
                    it.trackId.equals("bgm_$id", ignoreCase = true)
                }
        }
    }

    private val sounds = mutableMapOf<Sfx, Sound>()
    private val bgmSounds = mutableMapOf<BgmTrack, Sound>()
    private val attemptedSfx = mutableSetOf<Sfx>()
    private val attemptedBgm = mutableSetOf<BgmTrack>()

    /** One looping music voice. [level] (0..1) is its crossfade position, multiplied into the music volume. */
    private class Voice(val track: BgmTrack?, val customId: String?, val sound: Sound, val soundId: Long, var level: Float, var fadingOut: Boolean)

    /** Encoded (OGG) tracks a realm ships, by track id; they take precedence over a built-in theme of the same id. */
    private val customTrackBytes = mutableMapOf<String, ByteArray>()
    private val customSounds = mutableMapOf<String, Sound>()
    private val attemptedCustom = mutableSetOf<String>()

    /** The realm-shipped music currently playing (or fading in), or null. */
    val currentCustomTrackId: String? get() = voices.lastOrNull { !it.fadingOut }?.customId

    /** Makes [bytes] (an OGG file) playable as the music for maps whose `bgmTrackId` is [trackId]; decoded on first play. */
    fun registerCustomTrack(trackId: String, bytes: ByteArray) {
        val id = trackId.trim().lowercase()
        if (id.isEmpty()) return
        customTrackBytes[id] = bytes
        attemptedCustom.remove(id)
    }

    /** Forgets every realm-shipped track (stopping any that plays) so the next realm starts from the built-in music. */
    fun clearCustomTracks() {
        if (voices.any { it.customId != null }) stopBgm()
        customSounds.values.forEach { runCatching { it.dispose() } }
        customSounds.clear()
        customTrackBytes.clear()
        attemptedCustom.clear()
    }

    private fun loadCustom(id: String): Sound? {
        customSounds[id]?.let { return it }
        val bytes = customTrackBytes[id] ?: return null
        if (!attemptedCustom.add(id)) return null
        return runCatching { loader.loadEncodedTrack(id, bytes, "ogg") }.getOrNull()?.also { customSounds[id] = it }
    }

    private val voices = mutableListOf<Voice>()

    /** The music currently playing (or fading in), ignoring a track that is fading out. */
    val currentBgmTrack: BgmTrack? get() = voices.lastOrNull { !it.fadingOut }?.track

    var bgmVolume: Float = 0.4f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyBgmVolume()
        }

    /** Sound effect volume (0..1) on top of [masterVolume]; set from the Settings window. */
    var sfxVolume: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Mutes only sound effects; [muted] still mutes everything. */
    var sfxMuted: Boolean = false

    /** Mutes only the background music; the track keeps looping silently so unmuting resumes it. */
    var musicMuted: Boolean = false
        set(value) {
            field = value
            applyBgmVolume()
        }

    private fun bgmGain(): Float =
        if (muted || musicMuted || masterVolume <= 0f) 0f else (masterVolume * bgmVolume).coerceIn(0f, 1f)

    private fun applyBgmVolume() {
        for (v in voices) v.sound.setVolume(v.soundId, bgmGain() * v.level)
    }

    /** Advances music crossfades and warms up at most one short sound effect per frame; call once per frame. */
    fun update(deltaSeconds: Float) {
        if (attemptedSfx.size < Sfx.entries.size) {
            Sfx.entries.firstOrNull { it !in attemptedSfx }?.let { loadOneSfx(it) }
        }
        if (voices.isEmpty()) return
        val step = deltaSeconds / BGM_FADE_SECONDS
        val iterator = voices.iterator()
        while (iterator.hasNext()) {
            val v = iterator.next()
            if (v.fadingOut) {
                v.level -= step
                if (v.level <= 0f) {
                    stopVoice(v)
                    iterator.remove()
                    continue
                }
            } else if (v.level < 1f) {
                v.level = (v.level + step).coerceAtMost(1f)
            } else {
                continue
            }
            v.sound.setVolume(v.soundId, bgmGain() * v.level)
        }
    }

    private fun stopVoice(v: Voice) {
        if (v.soundId != -1L) v.sound.stop(v.soundId) else v.sound.stop()
    }

    /** Number of effects that loaded successfully (0 means the game is silent on this platform). */
    val loadedCount: Int get() = sounds.size

    val bgmLoadedCount: Int get() = bgmSounds.size

    private fun loadOneSfx(sfx: Sfx): Sound? {
        sounds[sfx]?.let { return it }
        if (!attemptedSfx.add(sfx)) return null
        return runCatching { loader.load(sfx, generateWavFor(sfx)) }
            .getOrNull()
            ?.also { sounds[sfx] = it }
    }

    private fun loadOneBgm(track: BgmTrack): Sound? {
        bgmSounds[track]?.let { return it }
        if (!attemptedBgm.add(track)) return null
        return runCatching { loader.loadTrack(track.trackId, generateWavFor(track)) }
            .getOrNull()
            ?.also { bgmSounds[track] = it }
    }

    fun loadAll() {
        for (sfx in Sfx.entries) loadOneSfx(sfx)
        for (track in BgmTrack.entries) loadOneBgm(track)
    }

    fun play(sfx: Sfx, volumeScale: Float = 1.0f) {
        if (muted || sfxMuted || masterVolume <= 0f || sfxVolume <= 0f) return
        val sound = sounds[sfx] ?: loadOneSfx(sfx) ?: return
        val vol = (masterVolume * sfxVolume * volumeScale).coerceIn(0f, 1f)
        sound.play(vol)
    }

    /** Plays a sound effect by string id (case-insensitive [Sfx] name); ignores unknown ids cleanly. */
    fun playById(soundId: String, volumeScale: Float = 1.0f) {
        val norm = soundId.trim().uppercase().replace('-', '_')
        if (norm.isEmpty()) return
        val sfx = Sfx.entries.firstOrNull { it.name == norm || it.name.replace("_", "") == norm.replace("_", "") }
        if (sfx != null) play(sfx, volumeScale)
    }

    fun playBgm(trackId: String) {
        val customId = trackId.trim().lowercase()
        if (customId in customTrackBytes) {
            if (customId == currentCustomTrackId) return
            val sound = loadCustom(customId)
            if (sound == null) { stopBgm(); return }
            val crossfading = voices.any { !it.fadingOut }
            voices.forEach { it.fadingOut = true }
            val startLevel = if (crossfading) 0f else 1f
            voices.add(Voice(null, customId, sound, sound.loop(bgmGain() * startLevel), startLevel, fadingOut = false))
            return
        }
        val track = BgmTrack.fromId(trackId)
        if (track == null) {
            stopBgm()
            return
        }
        if (track == currentBgmTrack) {
            return
        }
        // Crossfade: the old music fades out while the new one fades in. With nothing playing it starts at full volume.
        val crossfading = voices.any { !it.fadingOut }
        voices.forEach { it.fadingOut = true }
        val sound = bgmSounds[track] ?: loadOneBgm(track) ?: return
        val startLevel = if (crossfading) 0f else 1f
        voices.add(Voice(track, null, sound, sound.loop(bgmGain() * startLevel), startLevel, fadingOut = false))
    }

    /** Fades the music out over about a second (leaving a map that has none). */
    fun fadeOutBgm() {
        voices.forEach { it.fadingOut = true }
    }

    /** Stops all music immediately. */
    fun stopBgm() {
        voices.forEach { stopVoice(it) }
        voices.clear()
    }

    fun toggleMute(): Boolean {
        muted = !muted
        applyBgmVolume()
        return muted
    }

    fun dispose() {
        stopBgm()
        sounds.values.forEach { runCatching { it.dispose() } }
        sounds.clear()
        bgmSounds.values.forEach { runCatching { it.dispose() } }
        bgmSounds.clear()
        clearCustomTracks()
        attemptedSfx.clear()
        attemptedBgm.clear()
        (loader as? AutoCloseable)?.close()
    }

    companion object {
        /** Seconds for music to crossfade between maps. */
        const val BGM_FADE_SECONDS = 1.2f
        const val SAMPLE_RATE = 22050

        /**
         * Synthesizes raw 16-bit mono PCM samples for the specified [Sfx] and packages them into a WAV byte array.
         */
        fun generateWavFor(sfx: Sfx): ByteArray {
            val samples = when (sfx) {
                Sfx.SWING -> synthesizeNoiseSweep(durationMs = 90, startFreq = 480f, endFreq = 120f)
                Sfx.HIT -> synthesizeHitImpact(durationMs = 110, baseFreq = 160f)
                Sfx.CRIT -> synthesizeCritImpact(durationMs = 180, baseFreq = 220f)
                Sfx.SPELL -> synthesizeSpellSweep(durationMs = 170, startFreq = 260f, endFreq = 880f)
                Sfx.HEAL -> synthesizeArpeggio(notesHz = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.50f), noteDurationMs = 55)
                Sfx.LEVEL_UP -> synthesizeArpeggio(notesHz = floatArrayOf(440f, 554.37f, 659.25f, 880f), noteDurationMs = 110)
                Sfx.PICKUP -> synthesizeBlip(durationMs = 50, freq = 980f)
                Sfx.WARP -> synthesizeWarp(durationMs = 260)
                Sfx.CLICK -> synthesizeClick(durationMs = 16, freq = 1200f)
                Sfx.METEOR_DROP -> synthesizeArpeggio(
                    notesHz = floatArrayOf(1318.51f, 1046.50f, 1567.98f, 2093.00f),
                    noteDurationMs = 70
                )
                Sfx.DRAGON_BALL_DROP -> synthesizeArpeggio(
                    notesHz = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.50f, 783.99f, 1046.50f, 1318.51f, 1567.98f),
                    noteDurationMs = 85
                )
            }
            return buildWavContainer(samples, SAMPLE_RATE)
        }

        /**
         * Synthesizes raw 16-bit mono PCM samples for the specified [BgmTrack] and packages them into a WAV byte array.
         */
        fun generateWavFor(track: BgmTrack): ByteArray {
            val samples = when (track) {
                BgmTrack.TOWN_THEME -> synthesizeTownTheme()
                BgmTrack.FIELD_THEME -> synthesizeFieldTheme()
                BgmTrack.MARSH_THEME -> synthesizeMarshTheme()
                BgmTrack.DUNGEON_THEME -> synthesizeDungeonTheme()
            }
            return buildWavContainer(samples, SAMPLE_RATE)
        }

        private fun synthesizeTownTheme(): ShortArray {
            val melody = floatArrayOf(
                261.63f, 329.63f, 392.00f, 523.25f,
                440.00f, 392.00f, 329.63f, 293.66f,
                261.63f, 329.63f, 392.00f, 440.00f,
                392.00f, 349.23f, 293.66f, 261.63f
            )
            val bass = floatArrayOf(
                130.81f, 130.81f, 164.81f, 164.81f,
                174.61f, 174.61f, 196.00f, 196.00f,
                130.81f, 130.81f, 174.61f, 174.61f,
                196.00f, 196.00f, 130.81f, 130.81f
            )
            return renderChiptuneLoop(melody, bass, noteDurationMs = 220, waveType = 0)
        }

        private fun synthesizeFieldTheme(): ShortArray {
            val melody = floatArrayOf(
                293.66f, 369.99f, 440.00f, 587.33f,
                554.37f, 440.00f, 493.88f, 440.00f,
                369.99f, 440.00f, 493.88f, 587.33f,
                440.00f, 369.99f, 329.63f, 293.66f
            )
            val bass = floatArrayOf(
                146.83f, 146.83f, 146.83f, 146.83f,
                185.00f, 185.00f, 220.00f, 220.00f,
                196.00f, 196.00f, 146.83f, 146.83f,
                220.00f, 220.00f, 146.83f, 146.83f
            )
            return renderChiptuneLoop(melody, bass, noteDurationMs = 180, waveType = 1)
        }

        private fun synthesizeMarshTheme(): ShortArray {
            val melody = floatArrayOf(
                220.00f, 261.63f, 329.63f, 349.23f,
                329.63f, 293.66f, 261.63f, 246.94f,
                220.00f, 261.63f, 293.66f, 311.13f,
                329.63f, 293.66f, 246.94f, 220.00f
            )
            val bass = floatArrayOf(
                110.00f, 110.00f, 110.00f, 110.00f,
                130.81f, 130.81f, 123.47f, 123.47f,
                110.00f, 110.00f, 146.83f, 146.83f,
                164.81f, 164.81f, 110.00f, 110.00f
            )
            return renderChiptuneLoop(melody, bass, noteDurationMs = 260, waveType = 2)
        }

        private fun synthesizeDungeonTheme(): ShortArray {
            val melody = floatArrayOf(
                196.00f, 233.08f, 293.66f, 311.13f,
                293.66f, 277.18f, 293.66f, 233.08f,
                196.00f, 233.08f, 293.66f, 369.99f,
                349.23f, 293.66f, 233.08f, 196.00f
            )
            val bass = floatArrayOf(
                98.00f, 98.00f, 98.00f, 98.00f,
                116.54f, 116.54f, 98.00f, 98.00f,
                98.00f, 98.00f, 138.59f, 138.59f,
                146.83f, 146.83f, 98.00f, 98.00f
            )
            return renderChiptuneLoop(melody, bass, noteDurationMs = 200, waveType = 3)
        }

        private fun renderChiptuneLoop(
            melodyNotes: FloatArray,
            bassNotes: FloatArray,
            noteDurationMs: Int,
            waveType: Int
        ): ShortArray {
            val noteCount = melodyNotes.size
            val samplesPerNote = (SAMPLE_RATE * (noteDurationMs / 1000f)).toInt()
            val totalSamples = samplesPerNote * noteCount
            val out = ShortArray(totalSamples)

            var melodyPhase = 0f
            var bassPhase = 0f

            for (n in 0 until noteCount) {
                val mFreq = melodyNotes[n]
                val bFreq = bassNotes.getOrElse(n) { melodyNotes[n] / 2f }

                for (s in 0 until samplesPerNote) {
                    val idx = n * samplesPerNote + s
                    val t = s.toFloat() / samplesPerNote

                    melodyPhase += 2f * PI.toFloat() * mFreq / SAMPLE_RATE
                    bassPhase += 2f * PI.toFloat() * bFreq / SAMPLE_RATE

                    val mEnv = (1f - t * 0.4f).coerceIn(0f, 1f)
                    val mSample = when (waveType) {
                        0 -> (if (sin(melodyPhase) >= 0) 0.5f else -0.5f) + 0.3f * sin(melodyPhase * 2f)
                        1 -> (if (sin(melodyPhase) >= 0.2f) 0.6f else -0.6f)
                        2 -> sin(melodyPhase) * 0.8f + 0.2f * sin(melodyPhase * 3f)
                        else -> (if (sin(melodyPhase) >= 0) 0.7f else -0.7f)
                    } * mEnv

                    val bEnv = (1f - t * 0.6f).coerceIn(0f, 1f)
                    val bSample = sin(bassPhase) * bEnv

                    val mixed = (mSample * 0.6f + bSample * 0.4f)
                    out[idx] = (mixed * 24000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            return out
        }

        private fun synthesizeNoiseSweep(durationMs: Int, startFreq: Float, endFreq: Float): ShortArray {
            val totalSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt()
            val out = ShortArray(totalSamples)
            var phase = 0f
            var seed = 0x12345678
            for (i in 0 until totalSamples) {
                val t = i.toFloat() / totalSamples
                val currentFreq = startFreq + (endFreq - startFreq) * t
                phase += 2f * PI.toFloat() * currentFreq / SAMPLE_RATE
                seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
                val noise = ((seed ushr 16) - 16384) / 16384f
                val env = (1f - t) * (1f - t)
                val wave = (sin(phase) * 0.5f + noise * 0.5f) * env
                out[i] = (wave * 26000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }

        private fun synthesizeHitImpact(durationMs: Int, baseFreq: Float): ShortArray {
            val totalSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt()
            val out = ShortArray(totalSamples)
            var phase = 0f
            for (i in 0 until totalSamples) {
                val t = i.toFloat() / totalSamples
                val freq = baseFreq * (1f - t * 0.6f)
                phase += 2f * PI.toFloat() * freq / SAMPLE_RATE
                val square = if (sin(phase) >= 0) 1f else -1f
                val env = (1f - t) * (1f - t)
                val wave = square * env
                out[i] = (wave * 28000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }

        private fun synthesizeCritImpact(durationMs: Int, baseFreq: Float): ShortArray {
            val totalSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt()
            val out = ShortArray(totalSamples)
            var phase1 = 0f
            var phase2 = 0f
            for (i in 0 until totalSamples) {
                val t = i.toFloat() / totalSamples
                val freq1 = baseFreq * (1f - t * 0.4f)
                val freq2 = baseFreq * 2.5f
                phase1 += 2f * PI.toFloat() * freq1 / SAMPLE_RATE
                phase2 += 2f * PI.toFloat() * freq2 / SAMPLE_RATE
                val square = if (sin(phase1) >= 0) 0.6f else -0.6f
                val harmonic = sin(phase2) * 0.4f
                val env = (1f - t) * (1f - t)
                val wave = (square + harmonic) * env
                out[i] = (wave * 30000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }

        private fun synthesizeSpellSweep(durationMs: Int, startFreq: Float, endFreq: Float): ShortArray {
            val totalSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt()
            val out = ShortArray(totalSamples)
            var phase = 0f
            for (i in 0 until totalSamples) {
                val t = i.toFloat() / totalSamples
                val freq = startFreq + (endFreq - startFreq) * (t * t)
                val vibrato = 1f + 0.05f * sin(2f * PI.toFloat() * 18f * (i.toFloat() / SAMPLE_RATE))
                phase += 2f * PI.toFloat() * (freq * vibrato) / SAMPLE_RATE
                val env = (1f - t)
                val wave = sin(phase) * env
                out[i] = (wave * 28000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }

        private fun synthesizeArpeggio(notesHz: FloatArray, noteDurationMs: Int): ShortArray {
            val samplesPerNote = (SAMPLE_RATE * (noteDurationMs / 1000f)).toInt()
            val totalSamples = samplesPerNote * notesHz.size
            val out = ShortArray(totalSamples)
            var idx = 0
            for (noteFreq in notesHz) {
                var phase = 0f
                for (s in 0 until samplesPerNote) {
                    val t = s.toFloat() / samplesPerNote
                    phase += 2f * PI.toFloat() * noteFreq / SAMPLE_RATE
                    val env = (1f - t * 0.7f)
                    val wave = (sin(phase) * 0.7f + sin(phase * 2f) * 0.3f) * env
                    out[idx++] = (wave * 27000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            return out
        }

        private fun synthesizeBlip(durationMs: Int, freq: Float): ShortArray {
            val totalSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt()
            val out = ShortArray(totalSamples)
            var phase = 0f
            for (i in 0 until totalSamples) {
                val t = i.toFloat() / totalSamples
                phase += 2f * PI.toFloat() * freq / SAMPLE_RATE
                val env = (1f - t) * (1f - t)
                val wave = sin(phase) * env
                out[i] = (wave * 24000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }

        private fun synthesizeWarp(durationMs: Int): ShortArray {
            val totalSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt()
            val out = ShortArray(totalSamples)
            var phase = 0f
            for (i in 0 until totalSamples) {
                val t = i.toFloat() / totalSamples
                val freq = 600f * (1f - t * 0.75f)
                val lfo = 1f + 0.15f * sin(2f * PI.toFloat() * 12f * (i.toFloat() / SAMPLE_RATE))
                phase += 2f * PI.toFloat() * (freq * lfo) / SAMPLE_RATE
                val env = (1f - t)
                val wave = sin(phase) * env
                out[i] = (wave * 26000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }

        private fun synthesizeClick(durationMs: Int, freq: Float): ShortArray {
            val totalSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt()
            val out = ShortArray(totalSamples)
            var phase = 0f
            for (i in 0 until totalSamples) {
                val t = i.toFloat() / totalSamples
                phase += 2f * PI.toFloat() * freq / SAMPLE_RATE
                val env = (1f - t) * (1f - t)
                val wave = (if (sin(phase) >= 0) 1f else -1f) * env
                out[i] = (wave * 20000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }

        private fun buildWavContainer(samples: ShortArray, sampleRate: Int): ByteArray {
            val pcmDataSize = samples.size * 2
            val totalDataLen = pcmDataSize + 36
            val byteRate = sampleRate * 2
            val out = ByteArray(44 + pcmDataSize)

            fun putAscii(offset: Int, s: String) {
                for (i in 0 until s.length) out[offset + i] = s[i].code.toByte()
            }
            fun putShortLE(offset: Int, v: Int) {
                out[offset] = (v and 0xFF).toByte()
                out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
            }
            fun putIntLE(offset: Int, v: Int) {
                out[offset] = (v and 0xFF).toByte()
                out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
                out[offset + 2] = ((v ushr 16) and 0xFF).toByte()
                out[offset + 3] = ((v ushr 24) and 0xFF).toByte()
            }

            putAscii(0, "RIFF")
            putIntLE(4, totalDataLen)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putIntLE(16, 16) // Subchunk1Size for PCM
            putShortLE(20, 1) // AudioFormat (1 = PCM)
            putShortLE(22, 1) // NumChannels (1 = Mono)
            putIntLE(24, sampleRate)
            putIntLE(28, byteRate)
            putShortLE(32, 2) // BlockAlign (channels * bitsPerSample / 8)
            putShortLE(34, 16) // BitsPerSample
            putAscii(36, "data")
            putIntLE(40, pcmDataSize)

            var pos = 44
            for (s in samples) {
                val v = s.toInt()
                out[pos] = (v and 0xFF).toByte()
                out[pos + 1] = ((v ushr 8) and 0xFF).toByte()
                pos += 2
            }
            return out
        }
    }
}


/** Creates a [Sound] from the WAV bytes of one effect or music track; null when audio is unavailable. */
fun interface SoundLoader {
    fun load(sfx: RetroSoundEngine.Sfx, wav: ByteArray): Sound?
    fun loadTrack(trackName: String, wav: ByteArray): Sound? = null
    /** Same for an already-encoded file (a realm's OGG music); [extension] is its file type without the dot. */
    fun loadEncodedTrack(trackName: String, bytes: ByteArray, extension: String): Sound? = null
}

/** Desktop and Android: write the WAV to a temporary file and let libGDX load it from there. */
class TempFileSoundLoader : SoundLoader, AutoCloseable {
    private val tempFiles = mutableListOf<File>()

    override fun load(sfx: RetroSoundEngine.Sfx, wav: ByteArray): Sound? {
        if (Gdx.audio == null) return null
        val tempFile = File.createTempFile("morgana_sfx_${sfx.name.lowercase()}_", ".wav").apply {
            deleteOnExit()
            writeBytes(wav)
        }
        tempFiles.add(tempFile)
        return Gdx.audio.newSound(Gdx.files.absolute(tempFile.absolutePath))
    }

    override fun loadTrack(trackName: String, wav: ByteArray): Sound? {
        if (Gdx.audio == null) return null
        val tempFile = File.createTempFile("morgana_bgm_${trackName.lowercase()}_", ".wav").apply {
            deleteOnExit()
            writeBytes(wav)
        }
        tempFiles.add(tempFile)
        return Gdx.audio.newSound(Gdx.files.absolute(tempFile.absolutePath))
    }

    override fun loadEncodedTrack(trackName: String, bytes: ByteArray, extension: String): Sound? {
        if (Gdx.audio == null) return null
        val tempFile = File.createTempFile("morgana_bgm_${trackName.lowercase()}_", ".$extension").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        tempFiles.add(tempFile)
        return Gdx.audio.newSound(Gdx.files.absolute(tempFile.absolutePath))
    }

    override fun close() {
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
    }
}
