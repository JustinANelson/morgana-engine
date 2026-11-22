package com.morgana.domain

import com.morgana.domain.entity.MailMessageDocument
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.mail.MailRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailRulesTest {
    private fun letter(item: ItemStack? = null, claimed: Boolean = false) =
        MailMessageDocument("m1", "Ann", "c2", "Hi", "body", item, claimed = claimed)

    private fun item(id: Int = 1, bound: Boolean = false) = ItemDefinition(id = id, name = "Thing$id", boundOnPickup = bound)

    @Test
    fun `text limits are enforced`() {
        assertNull(MailRules.textProblem("Hello", "x".repeat(MailRules.MAX_BODY)))
        assertNotNull(MailRules.textProblem("  ", "body"))
        assertNotNull(MailRules.textProblem("s".repeat(MailRules.MAX_SUBJECT + 1), ""))
        assertNotNull(MailRules.textProblem("Hello", "x".repeat(MailRules.MAX_BODY + 1)))
    }

    @Test
    fun `bound and unknown items cannot be mailed`() {
        assertNull(MailRules.attachmentProblem(item()))
        assertNotNull(MailRules.attachmentProblem(item(bound = true)))
        assertNotNull(MailRules.attachmentProblem(null))
    }

    @Test
    fun `taking an attachment removes it from the sender bag`() {
        val bag = Inventory(maxSlots = 4).addItem(item(), 5).updatedInventory
        val (after, stack) = MailRules.takeAttachment(bag, 0, 3)!!
        assertEquals(3, stack.amount)
        assertEquals(2, after.totalCountOf(1))
        assertNull(MailRules.takeAttachment(bag, 0, 6))
        assertNull(MailRules.takeAttachment(bag, 0, 0))
    }

    @Test
    fun `claiming moves the attachment into the bag exactly once`() {
        val bag = Inventory(maxSlots = 4)
        val taken = MailRules.claim(letter(ItemStack(1, 2)), bag, item()) as MailRules.Claim.Taken
        assertEquals(2, taken.bag.totalCountOf(1))
        assertTrue(taken.message.claimed && taken.message.read)
        assertTrue(MailRules.claim(taken.message, taken.bag, item()) is MailRules.Claim.Refused)
        assertEquals(2, taken.bag.totalCountOf(1))
    }

    @Test
    fun `a full bag refuses the claim and keeps the letter claimable`() {
        val bag = Inventory(maxSlots = 1).addItem(item(9), 1).updatedInventory
        assertTrue(MailRules.claim(letter(ItemStack(1, 1)), bag, item()) is MailRules.Claim.Refused)
    }

    @Test
    fun `letters with an unclaimed attachment cannot be deleted`() {
        assertTrue(MailRules.canDelete(letter()))
        assertFalse(MailRules.canDelete(letter(ItemStack(1, 1))))
        assertTrue(MailRules.canDelete(letter(ItemStack(1, 1), claimed = true)))
    }

    @Test
    fun `a mailbox has a limit`() {
        assertTrue(MailRules.hasRoom(List(MailRules.MAX_INBOX - 1) { letter() }))
        assertFalse(MailRules.hasRoom(List(MailRules.MAX_INBOX) { letter() }))
    }
}
