package com.morgana.domain.mail

import com.morgana.domain.entity.MailMessageDocument
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack

/**
 * Pure rules for player mail: what a letter may contain, how an attachment is taken from the sender and handed to the
 * recipient, and when a mailbox is full. No I/O: the server stores the [MailMessageDocument]s and sends the packets.
 */
object MailRules {
    const val MAX_SUBJECT = 60
    const val MAX_BODY = 500

    /** Letters one mailbox may hold; the sender is told when it is full, so mail never silently vanishes. */
    const val MAX_INBOX = 50

    /** Why a letter cannot be sent, or null when its text is acceptable. */
    fun textProblem(subject: String, body: String): String? = when {
        subject.isBlank() -> "A letter needs a subject."
        subject.length > MAX_SUBJECT -> "The subject is too long (max $MAX_SUBJECT characters)."
        body.length > MAX_BODY -> "The letter is too long (max $MAX_BODY characters)."
        else -> null
    }

    /** Why [item] may not be mailed, or null when it can be. Items that are bound on pickup stay with their owner. */
    fun attachmentProblem(item: ItemDefinition?): String? = when {
        item == null -> "That item does not exist."
        item.boundOnPickup -> "${item.name} is bound to you and cannot be mailed."
        else -> null
    }

    /** The sender's bag after [amount] of the stack in [slotIndex] is taken out, with that stack; null when the slot cannot give it. */
    fun takeAttachment(bag: Inventory, slotIndex: Int, amount: Int): Pair<Inventory, ItemStack>? =
        if (amount <= 0) null else bag.removeFromSlot(slotIndex, amount)

    /** Whether [recipientMail] (the recipient's letters) has room for one more. */
    fun hasRoom(recipientMail: Collection<MailMessageDocument>): Boolean = recipientMail.size < MAX_INBOX

    /** The result of taking a letter's attachment into a bag. */
    sealed interface Claim {
        data class Taken(val bag: Inventory, val message: MailMessageDocument) : Claim
        data class Refused(val reason: String) : Claim
    }

    /**
     * Moves the letter's attachment into [bag]. All or nothing: when the whole stack does not fit, the bag and the
     * letter are unchanged and the player is told to make room, so an attachment can never be lost or split.
     */
    fun claim(message: MailMessageDocument, bag: Inventory, itemDef: ItemDefinition?): Claim {
        val stack = message.attachedItem ?: return Claim.Refused("This letter has nothing attached.")
        if (message.claimed) return Claim.Refused("You already took that attachment.")
        if (itemDef == null) return Claim.Refused("The attached item no longer exists.")
        if (bag.roomFor(itemDef) < stack.amount) return Claim.Refused("Your bag is too full for ${itemDef.name} x${stack.amount}.")
        val result = bag.addStack(itemDef, stack)
        if (result.overflowAmount > 0) return Claim.Refused("Your bag is too full for ${itemDef.name} x${stack.amount}.")
        return Claim.Taken(result.updatedInventory, message.copy(claimed = true, read = true))
    }

    /** A letter is worth keeping while it has an unclaimed attachment; deleting it would destroy the item. */
    fun canDelete(message: MailMessageDocument): Boolean = message.attachedItem == null || message.claimed
}
