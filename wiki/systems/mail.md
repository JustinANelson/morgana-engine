---
type: Simulation System
title: "Mail"
description: "Player-to-player letters with an optional item attachment: the MailRules domain rules, the mail module, packets, storage and the Mail window."
tags: [mail, social]
code: ["core-domain/src/main/kotlin/com/morgana/domain/mail/MailRules.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "client-core/src/main/kotlin/com/morgana/client/ui/PlayerHudAndWindows.kt"]
symbols: ["MailRules", "MailMessageDocument", "handleSendMail", "handleMailAction", "announceMailOnLogin", "SendMail", "MailAction", "MailInbox", "toggleMailWindow"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-14T12:00:00Z }
stale_after: 2027-05-13T00:00:00Z
---

# Mail

Players write letters to any character, online or not, with an optional attached item stack. It is the `mail` [optional module](/server/optional_modules.md) (on by default), so a realm can switch it off.

**Rules** (`MailRules`, pure, `MailRulesTest`): subject 1..60 characters (required), body up to 500, at most 50 letters in one mailbox (the sender is told "mailbox is full", so mail never silently vanishes), items bound on pickup cannot be attached, and a letter with an unclaimed attachment cannot be deleted. Claiming is all or nothing: if the whole stack does not fit the bag the letter stays unchanged and the player is told to make room.

**Flow** (`MorganaWorldCoordinator`, `MailServerTest`):
- `c_mail_send` (`SendMail(recipientName, subject, body, inventorySlotIndex = -1, amount)`): the recipient is found by name (online players first, then stored characters). The attachment is removed from the sender's bag with `setPlayerState` (so a replay sees the new bag), the `MailMessageDocument` is saved to `social/mail`, and an online recipient gets a chat notice plus a fresh `s_mail_inbox`. Mailing yourself is refused.
- `c_mail` (`MailAction(action, mailId)`): `REFRESH` (send the mailbox), `READ`, `CLAIM` (attachment into the bag) and `DELETE`. A letter that belongs to someone else is treated as gone.
- On login `announceMailOnLogin` sends the mailbox and "You have N unread letters." when there is any mail.
- The coordinator marks a replay gap for mail (like guild actions), so recordings note that mail is not replayable.

**Client**: `ClientWorldState.mailbox` holds the last `s_mail_inbox`; the **Mail** entry in the Menu opens a window with the inbox (unread letters marked `*`), a Take / Delete action row for the selected letter, and a compose form with an attachment picker over the bag. The window refreshes only when the mailbox changes, so typing is never lost.

**Not built yet**: money attachments, sending to a whole guild or party, return-to-sender after a time limit, and the player market (`MarketListingDocument` is still unused).
