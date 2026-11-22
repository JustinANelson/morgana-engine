---
type: Simulation System
title: "Ignore List"
description: "A per-character ignore list that hides chat, whispers, mail and invitations from chosen players: IgnoreRules, the ignore module, commands and the window."
tags: [ignore, chat, social]
code: ["core-domain/src/main/kotlin/com/morgana/domain/social/IgnoreRules.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "client-core/src/main/kotlin/com/morgana/client/ui/PlayerHudAndWindows.kt"]
symbols: ["IgnoreRules", "ignoredNames", "isIgnoredBy", "deliverChat", "changeIgnore", "handleIgnoreCommand", "IgnoreAction", "IgnoreList", "toggleIgnoreWindow"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-14T21:00:00Z }
stale_after: 2027-05-13T00:00:00Z
---

# Ignore List

The `ignore` [optional module](/server/optional_modules.md) (on by default). The list is `CharacterState.ignoredNames` (up to 100 names, compared ignoring case), saved with the character.

**What it hides** (`isIgnoredBy`, `deliverChat`): for the player who ignores, chat on the map, emote, global, party and guild channels, role-play speech, whispers, mail, and party and guild invitations from the ignored player never arrive. The ignored player is **not told**: a whisper still shows on their own screen, an invitation says "Invitation sent", mail is the one exception and is refused with "is not accepting letters from you" (the sender would otherwise lose the attachment). System messages and staff broadcasts are never filtered.

**Staff cannot be ignored** (access level above `PLAYER`), so a moderator talking to a player always gets through. `changeIgnore` refuses it and points to the report command (see [moderation](/server/moderation.md)).

**Use**: `c_ignore` (`IgnoreAction(REFRESH | ADD | REMOVE, name)`, answered with `s_ignore`), the chat commands `/ignore <name>`, `/unignore <name>` and `/ignorelist`, and the **Ignore** window from the Menu. The name must be an existing character. Changes mark a replay gap. Rules in `IgnoreRules` (pure, `GuildAndIgnoreRulesTest`), flow in `GuildAndIgnoreServerTest`.

**Not built yet**: ignoring a player's trade requests (trading is not filtered), an option to ignore by account instead of character name, and temporary ignores.
