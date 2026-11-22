package com.morgana.client.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.morgana.client.MorganaGame
import com.morgana.client.net.FileResumeStore
import com.morgana.client.net.MorganaNetClient

/**
 * Android Launcher Activity for the Morgana ORPG Client.
 * Defaults host to 10.0.2.2 (the Android Emulator's loopback alias to the host PC's 127.0.0.1:7001 server),
 * which can be edited in the in-game Login window for LAN or cloud servers.
 */
class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = AndroidApplicationConfiguration().apply {
            useAccelerometer = false
            useCompass = false
            useImmersiveMode = true
        }

        initialize(
            MorganaGame(
                transport = MorganaNetClient(),
                defaultHost = DEFAULT_EMULATOR_HOST,
                defaultPort = DEFAULT_SERVER_PORT,
                touchDevice = true,
                resumeStore = FileResumeStore(java.io.File(filesDir, "resume.json")),
                // Realm art on disk, as on desktop, so it is not downloaded again every time the app starts. The app's
                // cache folder: Android may clear it when storage runs low, and the files are then simply fetched again.
                assetCache = com.morgana.client.assets.FileAssetCache(java.io.File(cacheDir, "realm-assets"))
            ),
            config
        )
    }

    companion object {
        const val DEFAULT_EMULATOR_HOST: String = "10.0.2.2"
        const val DEFAULT_SERVER_PORT: Int = 7001
    }
}

