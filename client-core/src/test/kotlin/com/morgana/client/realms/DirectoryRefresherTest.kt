package com.morgana.client.realms

import com.morgana.protocol.realms.RealmDirectoryDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectoryRefresherTest {
    private class Client(var result: Result<RealmDirectoryDocument>) : RealmDirectoryClient {
        var calls = 0
        override fun fetchDirectory(): Result<RealmDirectoryDocument> { calls++; return result }
    }

    @Test
    fun `a refresh delivers the document on the ui side`() {
        val client = Client(Result.success(RealmDirectoryDocument(announcement = "hi")))
        val ui = ArrayDeque<() -> Unit>()
        val r = DirectoryRefresher(client, { it() }, { ui.addLast(it) })
        var got: Result<RealmDirectoryDocument>? = null
        assertTrue(r.refresh { got = it })
        assertTrue(r.loading)
        assertEquals(null, got) // not delivered until the ui side runs it
        ui.removeFirst()()
        assertFalse(r.loading)
        assertEquals("hi", got!!.getOrThrow().announcement)
    }

    @Test
    fun `a second refresh while one is running does nothing`() {
        val client = Client(Result.success(RealmDirectoryDocument()))
        val pending = ArrayDeque<() -> Unit>()
        val r = DirectoryRefresher(client, { pending.addLast(it) }, { it() })
        assertTrue(r.refresh { })
        assertFalse(r.refresh { })
        pending.removeFirst()()
        assertEquals(1, client.calls)
        assertTrue(r.refresh { }) // free again
    }

    @Test
    fun `a failure comes back as a failure and frees the refresher`() {
        val client = Client(Result.failure(RuntimeException("down")))
        val r = DirectoryRefresher(client, { it() }, { it() })
        var got: Result<RealmDirectoryDocument>? = null
        r.refresh { got = it }
        assertTrue(got!!.isFailure)
        assertFalse(r.loading)
    }
}
