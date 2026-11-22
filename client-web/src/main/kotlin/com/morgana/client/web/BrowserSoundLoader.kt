package com.morgana.client.web

import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.files.FileHandle
import com.github.xpenatan.gdx.teavm.backends.web.webaudio.howler.HowlSound
import com.morgana.client.audio.RetroSoundEngine
import com.morgana.client.audio.SoundLoader

/**
 * Plays the synthesized effects through Howler. The browser has no temp files, and gdx-teavm's [HowlSound] only reads
 * the bytes of the [FileHandle] it is given, so a handle that already holds the WAV bytes is enough.
 */
class BrowserSoundLoader : SoundLoader {
    override fun load(sfx: RetroSoundEngine.Sfx, wav: ByteArray): Sound? = HowlSound(InMemoryFileHandle(wav))
    override fun loadTrack(trackName: String, wav: ByteArray): Sound? = HowlSound(InMemoryFileHandle(wav))
    override fun loadEncodedTrack(trackName: String, bytes: ByteArray, extension: String): Sound? = HowlSound(InMemoryFileHandle(bytes))
}

private class InMemoryFileHandle(private val bytes: ByteArray) : FileHandle() {
    override fun readBytes(): ByteArray = bytes
    override fun length(): Long = bytes.size.toLong()
}
