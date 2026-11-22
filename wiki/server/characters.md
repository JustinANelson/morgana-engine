---
type: Concept
title: "Characters per Account"
description: "How one account holds several characters: the list after login, creating, choosing and deleting, the rules, and what older clients see."
tags: [server, client, accounts, protocol]
code: ["server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-app/src/main/kotlin/com/morgana/server/ResumeTokens.kt", "shared-protocol/src/main/kotlin/com/morgana/protocol/Packets.kt", "shared-protocol/src/main/kotlin/com/morgana/protocol/CharacterNames.kt", "client-core/src/main/kotlin/com/morgana/client/ui/CharacterSelectWindow.kt", "client-core/src/main/kotlin/com/morgana/client/realms/CharacterSelectModel.kt", "smoke-test/character-look.mjs"]
symbols: ["CharacterList", "CharacterSlot", "ChooseCharacter", "CreateCharacter", "DeleteCharacter", "CharacterNames", "CharacterSelectWindow", "CharacterSelectModel", "SessionConfig", "maxCharacters"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-13T12:00:00Z }
stale_after: 2027-05-12T00:00:00Z
---

# Characters per Account

An account used to be exactly one character (named after the username), so a second character meant a second account. An account now holds up to `session.maxCharacters` characters (default 3, 1 to 10; see [optional modules and server config](/server/optional_modules.md)).

## The flow
1. **Registering** a new account still creates its first character at once (named after the username, class from the sign-in form) and enters the world. Nothing changes for scripts and bots.
2. **Signing in again**, a client that advertised the capability `character_select` (the game client does; it is *not* in the default capability set, so older and scripted clients keep the one-step login) gets `s_character_list` instead of `s_auth_res`: the account's characters (name, class, level), the limit, and the classes a new character can have. The connection is signed in but not in the world yet. A realm with `maxCharacters` of 1 never shows the list.
3. The client answers with one of:
   - `c_choose_character(characterId)` plays it (`s_auth_res` follows, with a resume token that remembers *which* character, so a dropped connection resumes the same one).
   - `c_create_character(name, classId)` makes a character (the usual starting items, spells and position for the class) and plays it.
   - `c_delete_character(characterId, confirmName)` deletes one; the server answers with the updated list.
   A refused request comes back as the list again with the reason in `message` (name taken, limit reached, and so on). Before a successful sign-in all three are refused (`s_auth_res` failure) and cost login-budget packets, so they cannot be used to probe.

## Rules (all enforced by the server; the client only pre-checks)
- **Names** (`CharacterNames`): 2 to 20 characters of letters, digits, spaces, apostrophes, hyphens and underscores, no double spaces, at least one letter or digit. Unique among all characters regardless of case, and not another account's username.
- **Limit**: `maxCharacters` per account.
- **Delete**: the typed name must match; not while the character is in the world (including on another device); not while in a guild ("leave the guild first"). Deleting the last character is allowed (the list then offers to make one); a client without the capability is told the account has no character to play. The freed name can be reused.
- **One at a time**: an account has one character in the world at once; choosing another from a second device replaces the first (the first connection is told it signed in elsewhere), as signing in twice with the same character always did.

## The screen
`CharacterSelectWindow` (`:client-core`): one card per character (a click selects, a double click plays), **Play** and **Delete...** (which asks for the name typed), a name box with a class drop-down and **Create**, and **Back to realms**. It pings the server every 10 seconds while open, because nothing else does before the world, and returns to the sign-in screen if the connection is lost. `CharacterSelectModel` holds the wording and checks and is unit-tested. Preview it in the web client with `?fakeCharacters=2` (0 to 3); `smoke-test/character-look.mjs` takes screenshots.

## Tests
`CharacterSelectTest` (server flow: list or straight in, create rules, choose, resume remembers the character, replace, delete rules, no characters, refused before sign-in), `CharacterSelectPacketsTest` (round trips, older clients, name rules), `CharacterSelectModelTest`, and a step in the browser smoke test (log out, sign in, see the list, create a second character, play it).

## Not done yet
Switching character without leaving the world; renaming a character; per-character settings beyond the hotbar the client already keeps per name; a reserved or banned names list; showing a character's last map or playtime on its card; character slots as a paid or earned limit.
