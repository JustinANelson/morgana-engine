---
type: Simulation System
title: "Interaction, Inventory, and Equipment"
description: "Interact-facing, harvesting, pickup, item use, equipment, dropping, and stat allocation."
tags: [inventory, items]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/InventorySystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/item/ItemAndInventory.kt"]
symbols: ["playerInteractFacingImpl", "harvestResourceAt", "pickupGroundItemImpl", "useInventorySlotImpl", "allocateStatPointImpl", "DEFAULT_MAX_SLOTS"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Interaction, Inventory, and Equipment

- `playerInteractFacing` (`E`/`Enter`): checks the facing tile first (key doors, resource nodes, shop and bank tiles open their UI), then NPCs (shopkeeper opens shop, banker opens bank, quest giver runs its event), then `ACTION_INTERACT` events on the facing or current tile.
- **Harvest** (`harvestResourceAt`): needs the resource's `requiredTool` type; reduces node health (each swing shows the resource's `swingText`); on depletion pays `rewardItemId` x yield, then rolls each `bonusDrops` entry (a rare gem or Meteor; skipped when the bag is full), gives XP, and starts `respawnTicks`.
- `pickupGroundItem` (`G`): picks up the item on the player's tile.
- `useInventorySlot`: any item requires `levelReq` and (if set) `classReqIds`; `SPELL_SCROLL` items also enforce the taught spell's `levelReq` and `classReqIds` (emitting a warning and keeping the scroll when below level or of the wrong class: "Fireball is only for Fire Taoist."); consumables restore HP/MP/SP, apply status effects, and optionally teleport the player when `warpMapId > 0` (`warpX`, `warpY`); `SPELL_SCROLL` items with `teachSpellId` add the spell to `knownSpellIds`; equipment items equip into their slot.
- `unequipSlot`; `dropInventorySlot` puts the stack on the ground and refuses `boundOnPickup` items.
- `allocateStatPoint`: spends `unspentStatPoints` (granted per level by `ClassDefinition.statPointsPerLevel`, default 3).
- Inventory: `GameDefinition.inventorySlots` slots (default 24, 8..200; set in `game.json`; new characters get that many and existing ones **grow** to it at login, never shrink: `Inventory.grownTo`); bank: 48; hotbar: 10 slots (spells or items).
- `sortInventory` (packet `c_sort_inventory`, `SimInput.SortInventory`): merges stacks of the same item (up to each item's stack limit; gear keeps its durability) and packs the bag by item type (`ItemType` order: weapons, shields, helmets, armour, ... materials, keys), then name, then id (`Inventory.sorted`). Nothing is lost or created; sorting a tidy bag does nothing. `Inventory.roomFor(item)` says how many more of an item fit.

Related: [shops/bank/crafting](/systems/economy.md), [trading](/systems/trading.md), [character state](/domain/character_state.md).

**Equipment slots are data**: `EquipmentSlot` is a string id (`WEAPON`, `GARMENT`, ...) and the game's `equipmentSlots` (`EquipmentSlotDefinition`: `id`, `name`; default the classic nine) decides which exist. An item is worn in `ItemDefinition.equipSlot`, or, when that is blank, the slot of its `ItemType`. `useInventorySlotImpl` refuses an item whose slot the game lacks (`GameDefinition.hasSlot`), the content validator reports it as `unknown_slot`, and class auto-equip skips it. `WEAPON` is mandatory; two-handed weapons use `SHIELD` and bows use `AMMO` only if the game keeps those. Custom slots draw as paperdoll accessories unless the item sets `paperdollSlot`. The Character window lists the game's slots in order with their names.
