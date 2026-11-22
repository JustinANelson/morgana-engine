# Project Evaluation: Engine Modules, Plugins and Halcyon Reach Direction

**Written:** 2026-11-19 07:13 EST (UTC-05:00)
**Scope:** what the engine should ship with, which genre modules and optional plugins to add, and how Halcyon Reach should move from a Conquer Online Classic clone toward modern player expectations.
**Basis:** `docs/DESIGN.md`, `docs/GENRES_AND_MODULES.md`, `docs/PLUGIN_ARCHITECTURE.md`, `wiki/`, and `games/halcyon-reach/` (including `MISSING_FEATURES.md` and `RETHEME.md`) as of commit `c457925`.

This is a planning snapshot. Progress against it is logged under *Progress log* at the bottom.

---

## 1. Engine: what exists

- **Core:** deterministic 20 TPS simulation, pluggable combat (`realtime`, `noop`, `lua_combat`), data-driven stats and vitals, Lua scripting, Kotlin hook API.
- **Tooling:** `F1` live editor, content pipeline with one validator, world packs, game wiki generator.
- **Delivery:** desktop, Android and browser clients from one client core.
- **Optional modules (14):** parties, guilds, trading, crafting, projectiles, subTileMovement, softSeams, instancing, rolePlayChat, friends, exploration, ignore, market, mail.

## 2. Engine: gaps

1. ~~Client windows~~ **Correction (2026-11-19):** party, guild (with Officer ranks) and trade windows already exist; `docs/DESIGN.md` was stale (see `wiki/issues/design_doc_stale_statuses.md`). The one real gap was always-visible party frames, now built.
2. ~~Music~~ **Correction:** map BGM with crossfade and realm-shipped music already exist. Still open: the `Sound Emitter` tile attribute plays nothing, and Halcyon's `source-audio/` WAVs are not yet converted into shipped tracks.
3. ~~`F1` editors for events~~ **Correction:** event forms (`EventForms`) and an animation form exist.
4. **Threat table:** cast times are enforced; taunt is still a timed override with no threat.
5. **Area-of-interest networking:** no 30x30 cells yet.
6. **Mobile/web convenience:** Android invite-code and fingerprint UI pending.

## 3. What should ship with the engine (core)

- The finished party, guild and trade windows.
- Music.
- Event and animation editors.
- `journal`: quest log, bestiary, lore codex.
- `worldFlags`: server-shared switches and variables.
- Onboarding hooks and gamepad support.

## 4. Genre modules, ranked by payoff

| Module | Genre it unlocks | Why now |
| :--- | :--- | :--- |
| `farming` + `housing` | Cozy / life-sim | Fully designed (ADR 0015); world clock already built. |
| `mounts` / `pets` | ORPG | Halcyon's Steed is a stats-only stub. |
| `metaProgression` + `bossEncounters` | Roguelike, ORPG dungeons | Telegraphed boss phases help Halcyon world bosses. |
| `npcSchedules` | Cozy, RP | Small; makes towns feel alive. |
| `arenaMatchmaking` | PvP | Fits modern players better than open-world PvP alone. |
| `puzzleState`, `dodgeAction` | Action-adventure | Small and self-contained. |
| `cutscenes` | Narrative | Needs camera and move-route work. |
| `turnBasedCombat` | Tactical | Large; defer. |

## 5. Optional plugins (outside the core)

- Discord / webhook bridge; Prometheus metrics endpoint.
- Mongo document-store adapter (low priority).
- Balance analytics built on deterministic replay.
- Content marketplace on world packs (packs need to carry art and sound first).
- Headless bot / load-test harness.

## 6. Halcyon Reach

Currently a faithful Conquer Online Classic clone: about 34 hours to the level cap, heavy open-world PK, a level-15 reset on rebirth.

**Keep:** real-time combat and the 5 classes; gems, sockets and rarity affixes; guild war and the Holdfast; the seamless world; the economy model test.

**Change:**

1. **Pace:** target about 10-15 hours to cap; rested XP and catch-up bonuses.
2. **Rebirth:** replace the reset with a non-destructive paragon/mastery track.
3. **PvP:** opt-in by zone or flag; an instanced arena with no loss on death; full-loot risk only in Sunscald and Glasswaste.
4. **Death penalty:** cheap, clearly telegraphed (corpse run or durability cost).
5. **Movement:** sprint on the stamina bar; mounts as a movement tier (repurpose the Steed slot).
6. **Quality of life:** click-to-move pathing, quest tracker with waypoints, auto-loot, Town Portal Scroll to the nearest registered town, in-game bestiary, gear compare tooltip.
7. **Quests:** daily/weekly activity board, short regional story arcs, timed world events.
8. **Endgame:** scaling party dungeons with difficulty tiers and weekly lockouts (needs `bossEncounters`).
9. **Social:** finish guild ranks and hall extras; show stall goods in the stall window.
10. **Class depth:** per-class promotion skills, XP Accuracy skill, respec NPC.

## 7. Open decisions (owner)

| Decision | Recommendation |
| :--- | :--- |
| Rebirth: keep, soften, or replace with paragon | Replace with paragon |
| XP pace target | 10-15 hours |
| Mounts and pets: build shared module, repurpose Steed, or drop | Build and repurpose |
| PvP: opt-in with arenas, or open-world with consequences | Opt-in with arenas |
| First engine slice: farming/housing, or party/guild/trade windows | Windows first |

Until answered, work proceeds only on items that do not depend on these decisions.

## 8. Suggested order

1. ~~Party, guild and trade windows; cast times~~ already built; party frames added 2026-11-19.
2. ~~Music, then the event editor~~ already built; remaining: ship Halcyon's music, `Sound Emitter` playback.
3. Halcyon: XP pace, rebirth rework, QoL (portal scroll, sprint).
4. `mounts`/`pets`, `bossEncounters`, dungeon tiers.
5. `farming` and `housing`.

## Progress log

- 2026-11-19 07:13 EST: document written; starting step 1.
- 2026-11-19: step 1 audit found the windows, cast times, music and event forms already built and `docs/DESIGN.md` stale. Fixed `DESIGN.md`; added `PartyFramesModel`/`PartyFramesOverlay` (always-visible party frames) with tests.
- 2026-11-19: step 3 started. Owner approved all recommendations. Done: XP curve `30 * level^2` (about 13 h to cap); rebirth replaced by an engine `paragon` track; engine `sprint` (stamina, server-checked) and `townGate` (Town Portal Scroll, nearest town by map hops). Next: opt-in PvP and arena, death penalty, quest QoL, then `mounts`/`pets`, `bossEncounters`, dungeon tiers, `farming`/`housing`.
- 2026-11-19 (later): mounts (`mountSpeedPercent`, `MOUNT` slot, three mounts) and daily quests (`cooldownTicks`, three Bounty Clerks, six dailies) built and pushed.

## Status against the suggested order (end of 2026-11-19)

| Item | State |
| :--- | :--- |
| Party/guild/trade windows, cast times, music, event editor | Already built; docs corrected; party frames added |
| XP pace (about 13 h to the cap) | Done |
| Rebirth replaced by paragon | Done |
| Sprint | Done |
| Town Portal Scroll | Done |
| Mounts | Done (no mount sprites yet) |
| Daily quests | Done (weekly needs only a longer cooldown) |
| Opt-in PvP | Already the case: `PkMode` defaults to `PEACE`; no change needed |
| Instanced arena | Engine done 2026-11-19 (`arena` section, `/arena` queue, rated team matches); Halcyon has the Arena (`map_030`) and an Arena Master |
| Death penalty rework | Done (durability cost plus `/revive`) |
| Quest tracker waypoints, auto-loot, gear compare | Waypoints and a basic compare already existed; auto-loot and a full compare (plus levels, affixes, missing stats) added. Cross-map quest directions done 2026-11-19 |
| Rested XP / catch-up bonus | Done |
| `bossEncounters`, scaling dungeons with difficulty tiers and weekly lockouts | Engine done 2026-11-19 (phases, adds, telegraphed attacks, tiers, lockouts); Halcyon's Deepwyrm, Pale Regent and Ebbmother are scripted |
| Scaling party dungeons | Done: Undercroft 1F to 4F |
| Guild bank and hall extras, area-of-interest networking | Done 2026-11-19 |
| Threat table, bestiary | Done 2026-11-19 (on in Halcyon) |
| Daily/weekly board and weekly quests | Done 2026-11-19 (Bounty Board, four weekly contracts) |
| Respec NPC, XP Accuracy skill, Duelist Ironclad | Done 2026-11-19 |
| Endgame Marks sinks | Done 2026-11-19 (salves, mana draughts, respec) |
| Per-class promotion titles and skills | Done 2026-11-19 (engine `classNames`/`classGrantSpellIds`; Halcyon titles and spells 41 to 50) |
| `pets` | Done 2026-11-19 (engine and four Halcyon pets) |
| `journal` (quest log and bestiary exist; no lore codex), `worldFlags`, `npcSchedules`, `farming`, `housing` | Not started (bestiary done) |
- 2026-11-19 (evening): engine slice before content, as the owner asked: boss encounters (phases, adds, telegraphed attacks, difficulty tiers, lockouts), the arena (`/arena`, rated team matches), the threat table and the bestiary. Next: Halcyon content that uses them.
