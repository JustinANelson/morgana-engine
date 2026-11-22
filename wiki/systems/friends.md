---
type: Simulation System
title: "Friends List"
description: "A per-character friends list with online and offline notices: FriendRules, the friends module, packets and the Friends window."
tags: [friends, social]
code: ["core-domain/src/main/kotlin/com/morgana/domain/social/FriendRules.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "client-core/src/main/kotlin/com/morgana/client/ui/PlayerHudAndWindows.kt"]
symbols: ["FriendRules", "friendNames", "handleFriendAction", "notifyFriendWatchers", "announceFriendsOnLogin", "FriendAction", "FriendList", "FriendEntry", "toggleFriendsWindow"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-14T15:00:00Z }
stale_after: 2027-05-13T00:00:00Z
---

# Friends List

The `friends` [optional module](/server/optional_modules.md) (on by default). The list is `CharacterState.friendNames` (a set of character names, up to 100), so it is saved with the character and changes go through `setPlayerState`.

**Rules** (`FriendRules`, pure, `FriendRulesTest`): names compare ignoring case, the stored spelling is the character's own, you cannot add yourself or a duplicate, and the list has a limit. A friend must be an existing character (online or stored). The list is one-way: adding someone does not add you to theirs, and they are not told.

**Flow** (`MorganaWorldCoordinator`, `FriendsServerTest`): `c_friend` (`FriendAction(REFRESH | ADD | REMOVE, name)`) answers with `s_friends` (`FriendList` of `FriendEntry(name, online, level, mapId)`, online friends first). On login the player gets their list and "Friends online: ...", and every online player who lists them gets "X is now online." plus a fresh list; `removeFromWorld` sends "X is now offline." the same way. Friend changes mark a replay gap.

**Client**: `ClientWorldState.friends`; the **Friends** entry in the Menu opens a window listing friends (offline ones muted) with a Mail button (opens the [mail](/systems/mail.md) window addressed to them), a remove button, and an add box.

**Not built yet**: friend requests that need acceptance, notes, whisper from the list, and showing the friend's map name.
