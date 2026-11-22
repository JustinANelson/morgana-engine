package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisProgressBar
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisWindow
import com.morgana.client.assets.SyncProgress

/**
 * VisUI modal dialog presenting the download progress of a realm's assets.
 */
class AssetDownloadDialog(
    private val stage: Stage,
    private val onCancel: () -> Unit
) {
    private var window: VisWindow? = null
    private val progressBar = VisProgressBar(0f, 100f, 1f, false)
    private val statusLabel = VisLabel("Preparing asset download...")
    private val detailLabel = VisLabel("")

    fun isOpen(): Boolean = window != null && window?.stage != null

    fun show(realmName: String) {
        if (isOpen()) return

        val win = VisWindow("Synchronizing Realm Assets — $realmName")
        win.isModal = true
        win.isMovable = false
        win.isResizable = false
        win.defaults().pad(6f)

        win.add(statusLabel).fillX().row()
        progressBar.value = 0f
        win.add(progressBar).width(360f).padBottom(4f).row()
        win.add(detailLabel).fillX().padBottom(6f).row()

        val cancelBtn = VisTextButton("Cancel Download")
        cancelBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                onCancel()
                close()
            }
        })
        win.add(cancelBtn).padTop(4f).row()

        win.pack()
        win.centerWindow()
        stage.addActor(win)
        window = win
    }

    fun updateProgress(progress: SyncProgress) {
        progressBar.value = progress.percent * 100f
        if (progress.isFinished) {
            statusLabel.setText("All assets verified and loaded!")
            detailLabel.setText("Entering world...")
        } else {
            statusLabel.setText("Downloading: ${progress.currentFile}")
            val mbDownloaded = progress.downloadedBytes / 1048576.0
            val mbTotal = progress.totalBytes / 1048576.0
            val pct = (progress.percent * 100f).toInt()
            detailLabel.setText(
                "Files: ${progress.completedFiles}/${progress.totalFiles} | " +
                    "Size: %.1f MB / %.1f MB (%d%%)".format(mbDownloaded, mbTotal, pct)
            )
        }
    }

    fun setError(error: String) {
        statusLabel.setText("Download Failed!")
        detailLabel.setText(error)
    }

    fun close() {
        window?.remove()
        window = null
    }
}

