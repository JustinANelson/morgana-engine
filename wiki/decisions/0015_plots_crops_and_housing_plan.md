---
type: Decision
title: "ADR 0015: Plots, Crops, Owned Homes and the World Clock"
description: "Plan to give the engine plantable plots, owned instanced homes and a deterministic world clock, driven by what a content-only farming village could not express."
tags: [adr, farming, housing, roadmap]
code: ["docs/FARMING_AND_HOUSING.md"]
symbols: ["CropDefinition", "PlotState", "HomeLot", "WorldClock", "PlacedObject"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-08T12:00:00Z }
stale_after: 2027-05-07T00:00:00Z
---

# ADR 0015: Plots, Crops, Owned Homes and the World Clock

**Status**: Accepted on 2026-11-08; revised after a design review the same day. The world clock (step 1) and both prerequisites (world epoch persistence, protocol forward compatibility) are built; steps 2 and 3 are planned in their revised form. Full design: [`docs/FARMING_AND_HOUSING.md`](../../docs/FARMING_AND_HOUSING.md).

**Context** (the plan stands for any farming game): building a farming-village realm with content alone gave a playable shared farming village
but exposed three engine gaps: crops cannot be planted (resource nodes are fixed tiles with a shared regrow timer), houses cannot be
owned (the instanced-dungeon moral is only a label; interiors are shared; placed objects do not persist), and there is no world
clock for seasons or day and night.

**Decision**: add three independent, optional, tested capabilities in this order, each behind its own optional module and each as
data-defined, deterministic, replayable content:
1. **World clock** (`WorldClock`): derived from the tick, configured in `game.json`.
2. **Plots and crops** (`CropDefinition`, `PlotState`, the `plot` tile attribute): growth is a pure function of ticks planted, so it costs
   nothing per tick; planting, watering and harvesting are `SimInput`s; plot state becomes the first persisted world state.
3. **Owned homes** (`HomeInstance`, `PlacedObject`, furniture items): per-owner persisted instance maps built from a template, with
   visit permissions and placeable furniture; plots inside a home belong to its owner.

**Consequences**: a new persisted state family (plots, then homes), new packets and catalog entries, two new optional modules
(`farming`, `housing`) that the New Realm Wizard can switch, new `ItemType`s and `ToolType`s, and a content-collection (`crops`).
A game's field resource nodes migrate by swapping the field resource nodes for plot tiles and adding seeds to the shop. The planned
`WorldTopology` work is unaffected: instances are always discrete maps.

**Decisions** (owner, adopted 2026-11-08): both public allotments and claimed plots; crop care is a per-crop gradient (time only
by default, optional watering and wither); one home per account with an object cap; the calendar exists wherever a game declares a
`clock`, and visual day and night is opt-in (`clock.dayNight`).

## Revision (design review, 2026-11-08)
A review of the major design decisions changed the plan in four ways. The first two are firm (they fix defects); the last two are
**recommendations awaiting owner confirmation** before any code for steps 2 and 3.

1. **Prerequisite: persist the world epoch.** `WorldSimulation.currentTick` starts at 0 on every boot and is not saved, so the clock
   (step 1) restarts at "Spring, day 1" after each server restart. Before plots, the server saves the elapsed ticks (a `worldEpochTicks`
   in server state, restored at boot, added to the tick for calendar and growth maths). Plot growth, daily resets and NPC schedules
   all depend on it. Replays record and restore it like any other starting state.
2. **Prerequisite: protocol forward compatibility.** Unknown packet types currently throw when decoded, and `SeamView`,
   `SessionEnded` and `ResumeSession` were added without a version bump. Plots and homes add more packets, so first: decode unknown
   server packets to an ignorable `Unknown`, bump `PROTOCOL_VERSION` for breaking changes, and send a capability list at login so a
   server only sends what a client understands.
3. **Step 2 (crops): grow resource nodes instead of a parallel plot system** (recommended). A crop becomes a *growing resource*:
   `ResourceDefinition` gains an optional `growth` block (stages with durations and tiles, `needsWater`, `witherAfterTicks`,
   `regrowToStage`, `seasons`), and a new `plot` tile attribute marks where a seed item can be planted. Planting creates a persisted
   resource node on that tile; harvesting, tools, yields, XP, quests and the F1 resource editor all work unchanged. This drops the
   separate `CropDefinition` collection and most of the new packets (`MapResourceState` already ships to clients). Growth stays a pure
   function of ticks planted, public and claimed plots stay (`ownerOnly`), and resource state becomes persisted (the first persisted
   world state, which now also fixes wild nodes regrowing from scratch after a restart). The earlier `CropDefinition` design remains
   valid if growth needs prove richer than a stage list.
4. **Step 3 (homes): in-world lots first, instanced interiors second** (recommended). Instead of a private map copy per player,
   `HomeLot` tiles on a shared village map are claimed by one account each (one per account, as decided): the owner builds and
   decorates the lot's yard and house shell in the shared world, visitors simply walk there, soft seams and every existing system work,
   and there is no per-player instancing cost. Placed furniture persists per lot (`PlacedObject`, caps per lot). Interiors are an
   optional second layer: a lot's door can warp into an **instanced interior** once the engine has real instancing (which dungeons
   need anyway, so it is built once, later, for both). Lots can also be walled to be owner-only.

Both recommendations keep the owner's earlier decisions (public and claimed plots, optional crop care, one home per account,
opt-in day and night) intact.

Related: [New Realm Wizard](/server/new_realm_wizard.md), [optional modules](/server/optional_modules.md), [deterministic replay](/systems/deterministic_replay.md).
