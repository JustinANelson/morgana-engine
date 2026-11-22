package com.morgana.domain

import com.morgana.domain.social.FriendRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FriendRulesTest {
    @Test
    fun `adding ignores case and refuses self and duplicates`() {
        val one = FriendRules.add(emptySet(), "Ann", "Bob") as FriendRules.Change.Updated
        assertEquals(setOf("Bob"), one.friends)
        assertTrue(FriendRules.add(one.friends, "Ann", "bob") is FriendRules.Change.Refused)
        assertTrue(FriendRules.add(one.friends, "Ann", "ANN") is FriendRules.Change.Refused)
    }

    @Test
    fun `the list has a limit`() {
        val full = (1..FriendRules.MAX_FRIENDS).map { "F$it" }.toSet()
        assertTrue(FriendRules.add(full, "Ann", "Extra") is FriendRules.Change.Refused)
    }

    @Test
    fun `removing ignores case and reports a missing friend`() {
        val left = FriendRules.remove(setOf("Bob", "Cy"), "bOb") as FriendRules.Change.Updated
        assertEquals(setOf("Cy"), left.friends)
        assertTrue(FriendRules.remove(left.friends, "Bob") is FriendRules.Change.Refused)
    }

    @Test
    fun `sorting puts online friends first`() {
        val online = setOf("zed")
        assertEquals(listOf("zed", "Amy", "bob"), FriendRules.sorted(listOf("bob", "Amy", "zed")) { it in online })
    }
}
