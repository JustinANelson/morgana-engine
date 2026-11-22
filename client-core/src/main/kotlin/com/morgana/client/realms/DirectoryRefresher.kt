package com.morgana.client.realms

import com.morgana.protocol.realms.RealmDirectoryDocument

/**
 * Fetches the realm directory without freezing the screen. [runInBackground] runs the fetch off the render thread
 * (or inline for a client that answers instantly) and [postToUi] brings the result back to it. Only one fetch runs
 * at a time; asking again while one is in flight does nothing.
 */
class DirectoryRefresher(
    private val client: RealmDirectoryClient,
    private val runInBackground: (() -> Unit) -> Unit,
    private val postToUi: (() -> Unit) -> Unit
) {
    var loading: Boolean = false
        private set

    /** Starts a fetch. [onDone] gets the document, or the failure, on the UI thread. Returns false when one was already running. */
    fun refresh(onDone: (Result<RealmDirectoryDocument>) -> Unit): Boolean {
        if (loading) return false
        loading = true
        runInBackground {
            val result = runCatching { client.fetchDirectory().getOrThrow() }
            postToUi {
                loading = false
                onDone(result)
            }
        }
        return true
    }
}
