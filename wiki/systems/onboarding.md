---
type: Simulation System
title: "New-Player Tips (Onboarding)"
description: "Short hints shown once per account as they become relevant: a welcome, equipping, stat points, skills, gear repair, ghosts, low health and level milestones; built in, rewritable per game in game.json."
tags: [onboarding, tips, ui, accounts]
code: ["core-domain/src/main/kotlin/com/morgana/domain/onboarding/Onboarding.kt", "server-app/src/main/kotlin/com/morgana/server/TipService.kt", "client-core/src/main/kotlin/com/morgana/client/ui/Tips.kt", "client-core/src/main/kotlin/com/morgana/client/ui/TipWindow.kt", "shared-protocol/src/main/kotlin/com/morgana/protocol/Packets.kt", "core-domain/src/main/kotlin/com/morgana/domain/entity/EntityAndPlayerModels.kt"]
symbols: ["TipTrigger", "TipDefinition", "OnboardingConfig", "OnboardingDefaults", "OnboardingRules", "TipService", "TipQueue", "TipText", "TipWindow", "ServerPacket.Tip", "ClientPacket.TipsControl", "TipsAction", "tipsSeen"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-17T18:00:00Z }
stale_after: 2027-05-16T00:00:00Z
---

# New-Player Tips (Onboarding)

Small cards that explain a feature at the moment it matters, so a new player is not handed a manual. Each tip is shown **once per account** (the ids are stored in `AccountDocument.tipsSeen`), so a second character never repeats them. Nothing here touches the simulation, so replays and determinism are unaffected.

## What triggers a tip
`TipTrigger` (`core-domain`, `OnboardingRules`). Most are read from the character's *state*, so they fire however the state came about; `WELCOME` and `DEATH` come from the login and the death event.

| Trigger | When | Built-in tip |
| :--- | :--- | :--- |
| `WELCOME` | first login of the account | movement, interact, attack, pick up, the Controls list |
| `EQUIPMENT_FOUND` | the bag holds gear the character can wear (level, class) whose slot is empty | how to equip |
| `STAT_POINTS` | unspent attribute points | the +1 buttons on the Stats tab |
| `SKILL_AVAILABLE` | a skill or spell is within the character's level and class and not yet known (only if the game shows a spells window) | the skills window and quickbar |
| `GEAR_WORN` | worn gear under 100 durability (only with `durability`) | gear wears down, repair it for a fee |
| `GEAR_BROKEN` (urgent) | worn gear at 0 durability | it no longer counts, repair it |
| `LOW_HEALTH` (urgent) | alive with under 30% life | heal, retreat |
| `DEATH` (urgent) | a death | with `ghost`: you are a ghost, a reviver brings you back or `/release`, auto-release time, any `/revive` wait; without: you were sent back, dying can cost you |
| `LEVEL` | the character reached the tip's `level` | none built in; for a game's own milestones |

A ghost gets no state tips. Tips whose feature the game does not use (no durability, no spells window, no allocatable attributes) are not built in at all.

## Delivery
`TipService` (`server-app`): the coordinator calls it on login, on every `CharacterUpdated` and on `PlayerDied`. A character is looked at at most once a second; an ordinary tip waits 30 seconds after the last one, an urgent one 5 seconds, and a tip is sent only to a client that advertised the `tips` capability (the standard client does), so a bot or older client loses nothing and the tip stays unseen. Urgent tips come first, then the game's order. The account's state is cached and written through.

The client (`TipQueue`, `TipWindow`) shows one card at a time under the top bar with **Got it** and **Turn off tips**; it closes itself after a minute. Text may use `{key:ACTION}` (the player's own key for a game action such as `INVENTORY`, so rebinding never leaves a tip lying) and `{abilities}` (the game's word for skills or spells), filled in by `TipText`.

Players control them with `/tips off`, `/tips on` and `/tips reset` (show every tip again), or the card's button ([ClientPacket.TipsControl](/protocol/client_packets.md)). `ServerPacket.Tip` is new, so no protocol version bump; see [server packets](/protocol/server_packets.md).

## game.json
`"onboarding": { "enabled": true, "defaults": true, "tips": [ { "id": "death", "trigger": "DEATH", "title": "...", "text": "..." } ] }`. Without the section a game gets the built-in tips. `enabled: false` shows none; `defaults: false` drops the built-in ones; a tip with the id of a built-in replaces it; the same id with blank text removes it; any other id adds a tip (a `LEVEL` tip also needs `level`). Ids are `A-Za-z0-9_`, at most 40 characters; titles 60, text 500; at most 40 tips. Validated by `GameDefinitionValidator` (`/onboarding/...`). Trevana Realms rewrites the repair, skills, death and welcome tips for its own world and adds promotion (level 30) and ascension (level 120) tips.

Tests: `OnboardingRulesTest` (rules, merging, validation), `TipServiceTest` (once per account, throttling, urgency, persistence, `/tips`), `TipPacketsTest`, `TipsTest` (client queue and placeholders), `TrevanaRealmsGameTest`.
