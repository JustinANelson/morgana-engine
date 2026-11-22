# Plots, Crops, Owned Homes and the World Clock: Design

**Status (2026-11-14):** the world clock and both prerequisites are built; plots and crops (B) and owned homes (C) are planned and not started. Decision record: [ADR 0015](../wiki/decisions/0015_plots_crops_and_housing_plan.md).

The plan came from building a farming-village game on the engine with content alone. That worked for a shared village (gather nodes, shops, recipes, quests) but exposed three gaps, which this design closes for any game that wants them: crops cannot be **planted** by a player, a house cannot be **owned**, and there was no **calendar**. The three additions are independent and ordered: **A. world clock**, **B. plots and crops**, **C. owned homes**. Each ships behind its own optional module and with its own tests, so a game that does not want farming or housing pays nothing.

## Principles
* **Deterministic.** Time is the simulation tick. Growth is a pure function of ticks planted, never a timer thread; random yield uses the seeded `RandomSource`. Every state change is a `SimInput`, so it appears in the replay log.
* **Data first, GUI optional.** Crops and homes are JSON content with schemas and validation, authorable by hand, by script or in `F1`.
* **Domain stays pure.** Rules in `:core-domain`, storage in `:server-persistence`, packets in `:shared-protocol`.
* **Optional modules.** `farming` and `housing` become `OptionalModule`s, switchable per realm and in the New Realm Wizard.
* **Tests before the next step**, including replay equality and persistence round trips.

## Decisions (owner, 2026-11-08)
1. **Plot ownership: both.** Public allotments (`ownerOnly = false`, anyone plants and picks) and claimed plots (`ownerOnly = true`, only the planter waters or picks).
2. **Crop care: a gradient per crop.** Time only by default; `needsWater` and `witherAfterTicks` are opt-in.
3. **Homes: one per account**, with an object cap per home; purchasable plots and upgrades come later.
4. **Day and night: opt-in per game** (`clock.dayNight`); the calendar exists wherever a game declares a `clock`.

## A. World clock: built
`ClockDefinition` / `WorldClock` / `ClockTime` (`:core-domain`), a `clock` section in `game.json` (validated, in the schema), `WorldSimulation.clockNow()`, Lua `world.clock`, `serverTick` in `AuthResult` and `MapStateSnapshot` (the client keeps its own estimate, so the clock costs no traffic), a darkened outdoor view at night when `dayNight` is on, and a HUD line with season, day and time. Everything derives from the tick: `dayLengthTicks`, `daysPerSeason` and `seasons` come from `game.json`. See [game definition](../wiki/domain/game_definition.md). Tests: `WorldClockTest`, `WorldClockScriptTest`, `GameClockClientTest`.

## Prerequisites: built
* **World epoch persistence.** `worldEpochTicks` is saved in server state and restored at boot, and the calendar and growth maths use `effectiveWorldTick()`, so a restart no longer resets the season. Replays restore it as starting state.
* **Protocol forward compatibility.** Unknown packets decode to an ignorable `Unknown`, `PROTOCOL_VERSION` is bumped for breaking changes, and the client sends a capability list at login so a server only sends what it understands.

## B. Plots and crops: planned (medium)
**Crops are growing resource nodes**, not a parallel system.
* `ResourceDefinition` gains an optional `growth` block: `stages[] { name, durationTicks, tileset, srcTileX, srcTileY }` (the last stage is ripe), `needsWater`, `witherAfterTicks` (0 = never), `regrowToStage`, `seasons[]` (empty = all year).
* New `plot` tile attribute (`tillable`, `ownerOnly`) marks where a seed can be planted; new `ItemType.SEED` (with `plantsResourceId`) and `ToolType.WATERING_CAN` / `HOE`.
* Planting a seed on an empty plot creates a persisted resource node there. The stage is **derived** (`f(ticks planted, watering)`), not ticked, so a field of a thousand plots costs nothing per tick. Harvesting reuses `harvestResourceAt`, so tools, yield, XP, quests and the F1 resource editor work unchanged. Withering and regrowing are likewise derived.
* Resource node state becomes persisted for the first time (this also fixes wild nodes losing their regrow timers on restart), through a `WorldStateRepository`. `MapResourceState` already reaches clients, so it only gains a stage field and a plant action.

| Action | Input | Rule |
| :--- | :--- | :--- |
| Plant | `UseInventorySlot` on a seed facing an empty plot | consumes a seed; checks season, `ownerOnly`, module on |
| Water | `UseInventorySlot` on a watering can facing a planted plot | sets a watered-until tick; a water-source tile refills the can |
| Harvest | `InteractFacing` on a ripe plot | seeded yield, XP, regrow or clear |
| Till | `UseInventorySlot` on a hoe facing a `tillable` plot | marks the ground tilled |
| Clear | `InteractFacing` on a withered plot | removes it |

All five reuse existing facing and use paths, so touch controls keep working. Validation (`validateContent`) checks that seed and produce items exist, stages are non-empty with positive durations, tiles fit the tileset and seasons are valid. `F1` gains a Plot attribute in the map editor and growth fields in the Resources editor.
Tests: stage derivation, watering, wither, season gating and regrow; plant, water, harvest, ownership, module off and wrong tool; save/load and restart mid-growth; a recorded session replays to identical state.

*Fallback:* if growth needs outgrow a stage list, a separate `CropDefinition` collection with its own `PlotState` is the heavier alternative.

## C. Owned homes: planned (large)
**In-world lots first, instanced interiors second.**
* `HomeLot` tiles on a shared village map: one account claims one lot. The owner builds and farms the lot's yard (and optionally a house shell) in the shared world, visitors just walk there, and soft seams and every existing system keep working. Plots inside a claimed lot are `ownerOnly` for its owner.
* Placed furniture persists per lot (`PlacedObject { itemId, tileX, tileY, rotation }`, `maxObjects` per lot, owner-only placement, optional owner-only walls), backed by `ItemType.FURNITURE` with an optional function (`bed`, `storage`, `craftingStation`) that reuses the event and station mechanics.
* Later, a lot's door can warp into an **instanced interior**. Map instancing already exists for dungeons ([instanced maps](../wiki/systems/instancing.md)), but homes need per-owner persisted copies, which it does not do yet.
* Commands: claim and release a lot, `place_furniture`, `pick_up_furniture`, access policy (`OWNER_ONLY | INVITED | PUBLIC`) and invites, as `ClientPacket`s or slash commands first. Persistence is a `HomeRepository` (one document per owner); an object that a content change makes invalid returns to its owner.
* Safety: caps on objects, access checks on every action, flood-limited packet classes; `F1` admin tools to list, teleport to and clear homes.

Tests: claim and release, per-owner isolation, persistence and restart, access policies, placement validation and caps, replay equality, and an end-to-end test over real sockets.

## Order and risks
**Order:** A (done), then B, then C; C is worth starting only after B proves the world-state persistence pattern.
**Risks:** persisted world state is new, so the snapshot format and replay need care (a recording must start from a known plot state); placement UI on small screens is real work; instanced interiors depend on per-owner persistence that dungeon instancing does not provide.
