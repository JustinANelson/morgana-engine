---
type: Reference
title: "Tutorial Village: the Getting-Started Game"
description: "games/tutorial-village, the finished game of docs/GETTING_STARTED.md (a village, a gardener, a kill quest and its reward), kept playable by TutorialVillageGameTest."
tags: [content, tutorial, onboarding, reference]
code: ["games/tutorial-village/game.json", "games/tutorial-village/data", "docs/GETTING_STARTED.md", "game-tests/src/test/kotlin/com/morgana/gametests/TutorialVillageGameTest.kt", "distribution/build.gradle.kts"]
symbols: ["TutorialVillageGameTest"]
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-14T22:30:00Z }
stale_after: 2027-05-13T00:00:00Z
---

# Tutorial Village (`games/tutorial-village`)

Added 2026-11-14 (owner request: "work on the onboarding guide"). [`docs/GETTING_STARTED.md`](../../docs/GETTING_STARTED.md) is a "first game in 15 minutes" walkthrough that uses only the client's `F1` editors (no JSON, no code): paint a village, make an item, two NPCs, a quest and a three-page conversation, then play it. This folder is the finished result, so a reader can pick *Tutorial Village* in the New Realm Wizard and take apart what the guide builds. It uses the [blank game](/content/blank_game.md)'s rules and the engine's built-in tiles and sprites.

## Content

| Kind | What |
| :--- | :--- |
| Map 1 **Greenhollow** | 20 x 20, safe. A cobble crossroads, a pond, a fenced vegetable garden in the north-east with the pests, tree border (Blocked). |
| Items | 1 Bronze Sword (from the blank game), 2 **Gardener's Charm** (amulet, +2 vit). |
| NPCs | 1 **Mara** (Gardener, quest giver, `eventId` 1), 2 **Garden Pest** (Nibbler, aggressive slime, 20 hp, 15 xp). |
| Quest 1 **Weed the Garden** | `KILL_NPC 2 3`; reward 100 xp and item 2. |
| Event 1 **Mara: the garden quest** | Page 1 offers the quest ("I'll help" / "Not right now"); page 2 (quest in progress) hands in when ready, otherwise reminds; page 3 (quest completed) thanks the player. |
| Class 1 Adventurer, `scripts/main.lua` | As in the blank game. |

**Id difference from the guide**: a reader following the guide starts from *Blank Game*, which already has the Town Guide as NPC 1, so their Mara is NPC 3. The finished game has no Town Guide and Mara is NPC 1. The ids the guide makes the reader type (pest 2 in the quest objective, item 2 in the reward) are the same in both.

## How it is kept truthful
- `TutorialVillageGameTest` (runs on a copy in a temp folder): the content validates and has what the guide names; the quest can be taken from Mara, finished by killing three pests, handed in, and the charm arrives; answering "Not right now" leaves the quest unstarted.
- The guide's UI labels were checked against the client source (`EventForms`, `NpcForm`, `ChoiceText`, the item, quest and attribute panels) and its editor screens against the browser client running this game (2026-11-14).
- When an editor label, the wizard or the blank game's ids change, update `docs/GETTING_STARTED.md` with it.

## Where it shows up
- The New Realm Wizard and `morgana world-new --template tutorial-village` list it automatically, like any folder under `games/` ([new realm wizard](/server/new_realm_wizard.md)).
- The [SDK](/playbooks/build_the_sdk.md) ships it as `games/tutorial-village/`, and ships `docs/GETTING_STARTED.md` (plus `GENRES_AND_MODULES.md` and `PLUGIN_ARCHITECTURE.md`, which the guide links to). The SDK README and the repository README point new users at the guide.
- Run it from source with `.\gradlew.bat :server-app:run --args="--game games/tutorial-village"`.

Related: [Lanternfall](/content/roguelike_game.md), the larger worked example; [F1 editor](/client/f1_editor.md).
