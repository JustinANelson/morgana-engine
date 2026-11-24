---
type: Simulation System
title: "Loot Rarities and Affixes"
description: "Diablo-style random item properties: the loot section of game.json, how dropped gear rolls a rarity and affixes, how rolls travel with the item, count while worn, change names, colours and prices."
tags: [loot, items, economy, roguelike]
code: ["core-domain/src/main/kotlin/com/morgana/domain/item/ItemRolls.kt", "core-domain/src/main/kotlin/com/morgana/domain/item/ItemAndInventory.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/CombatSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/InstanceSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/GameDefinitionValidator.kt", "client-core/src/main/kotlin/com/morgana/client/ui/bag/BagModels.kt"]
symbols: ["LootRules", "RarityDefinition", "AffixDefinition", "AffixStat", "ItemRolls", "RolledAffix", "ItemRoller", "ItemRollText", "addStack", "rolledAttackDamage", "itemLevel", "rollAffixes"]
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-14T23:30:00Z }
stale_after: 2027-05-13T00:00:00Z
---

# Loot Rarities and Affixes

Added 2026-11-14, item 1 of the Diablo-style demo plan ([roadmap](/reference/roadmap_status.md)). A game opts in with a `loot` section in `game.json`; without one nothing changes (items drop plain, and the world's random numbers are drawn exactly as before).

## The rules (`game.json` → `loot`, `LootRules`)
```json
"loot": {
  "rarities": [
    { "id": "common", "name": "Common", "color": "#d1d5db", "weight": 600 },
    { "id": "magic",  "name": "Magic",  "color": "#60a5fa", "weight": 300, "affixesMin": 1, "affixesMax": 2, "priceMultiplierPercent": 250 },
    { "id": "rare",   "name": "Rare",   "color": "#facc15", "weight": 90,  "minItemLevel": 3, "affixesMin": 3, "affixesMax": 4, "priceMultiplierPercent": 600 }
  ],
  "affixes": [
    { "id": "sharp", "name": "Sharp", "position": "PREFIX", "stat": "ATTACK_DAMAGE", "min": 1, "max": 3, "maxItemLevel": 4, "slots": ["WEAPON"] },
    { "id": "of_the_bear", "name": "of the Bear", "position": "SUFFIX", "stat": "ATTRIBUTE", "statId": "vit", "min": 3, "max": 5, "minItemLevel": 5 }
  ]
}
```
- **Rarity** (`RarityDefinition`): `weight` (relative chance; **0 means it never drops** but is still a valid rarity, for example a quality only a crafter's upgrade reaches; the list order is the quality ladder), `minItemLevel`, `affixesMin..affixesMax`, `color` (`#rrggbb`, used for the name and the ground marker), `priceMultiplierPercent` (sell value).
- **Affix** (`AffixDefinition`): `stat` is `ATTRIBUTE` or `VITAL_MAX` (with `statId` naming the attribute or vital), `ATTACK_DAMAGE` (weapon damage from any slot), `ARMOR_DEFENSE`, `CRIT_CHANCE_BP` (basis points), `DODGE_CHANCE_BP`, `LIFE_LEECH_PERCENT`, or `MAGIC_FIND_PERCENT`; a value range `min..max`; `weight`; the item levels it rolls on (`minItemLevel..maxItemLevel`, so stronger tiers of one bonus are separate affixes); `slots` it may appear on (empty = any gear but ammunition); `position` `PREFIX` or `SUFFIX` decides where its `name` goes.
- **Best tier**: `LootRules.topRarityId` is the last rarity listed (list them lowest first; a game with one tier has none). Worn gear of that rarity is drawn with a 1 pixel yellow glow on the paper doll ([rendering](/client/rendering.md)).
- **Validation** (`GameDefinitionValidator.checkLoot`): ids unique and lowercase, names 1..24 (rarity) or 1..32 (affix) characters, colours `#rrggbb`, weights 1..1,000,000, item levels 1..1000, at most 8 affixes per item, 12 rarities and 400 affixes, `affixesMin <= affixesMax`, `min <= max` within ±100,000, `statId` must name an attribute or vital (empty for damage and armour), no ammunition slot, at least one rarity allowed at item level 1, and a rarity with affixes needs affixes defined.

## Which items roll, and when (`ItemRoller`)
- **Eligible**: equippable gear that is not ammunition and does not stack, unless the item sets `"rollAffixes": false` (fixed quest rewards, named uniques such as Lanternfall's Warden's Helm).
- **Only drops roll**: monster drops (`handleNpcKilled`, item level = the monster's `level`) and loot lying on a generated floor (`openGeneratedFloor`, item level = the recipe's `itemLevel`, see [procedural floors](/systems/procedural_floors.md)). Shops, quest rewards, crafting, event and script `GiveItem`, admin `/item` and starting items hand out plain items.
- A drop of N pieces of gear becomes N ground items, each with its own roll.
- **Rolling**: pick a rarity by weight among those allowed at the item level; draw the affix count in the rarity's range; draw that many affixes by weight from those allowed for the slot and level, never the same affix twice and never two affixes on the same stat (so tiers are alternatives); draw each value in its range. Fewer affixes when the pool runs out.
- **Determinism**: monster drops use the world's seeded random numbers (only when the game has loot rules and the drop is gear); floor loot uses its own `SeededRandomSource` from the floor's seed (the fixed seed when the recipe sets one) mixed with a salt, so a fixed-seed floor lays identical loot and the world sequence is untouched. Replays reproduce both.

## On the item (`ItemStack.rolls`, `ItemRolls`)
`ItemStack` gained `rolls: ItemRolls?` (rarity id, item level, rolled affixes). Each `RolledAffix` copies its stat and stat id from the definition, so bonuses keep working and can be totalled without the loot table; the name comes from the table when shown. Old saves and packets without the field load as plain items; the field rides in every packet and document that already carries an `ItemStack` (bag, bank, equipment, ground items, trade offers, mail attachments, market listings), so there is no new packet and no protocol version bump. Older clients ignore it.

**Moving items keeps rolls**: `Inventory.addStack(def, stack)` places a non-stackable item as a copy of the stack (rolls and durability kept) and merges stackables like `addItem`. It is used for pickup, equip swaps and unequip, bank deposit and withdraw, trade completion, mail claims, market purchases and cancellations. `addItem` remains for newly created items.

## While worn
`EquipmentLoadout` adds rolled affixes to `totalAttributeBonuses` (attributes), `totalVitalMaxBonuses` (vital maxima), `totalArmorDefense` (armour), and `rolledAttackDamage` (added to the weapon's damage in melee and ranged attacks in `CombatSystem`). Lua scripts see the weapon's item id only (`weapon_item_id`), so a Lua combat module does not see rolled damage yet.

## Names, colours and prices (`ItemRollText`)
- Name: the first prefix, the item's name, the first suffix ("Cruel Sword of the Bear").
- Colour: the rarity's colour, for the tooltip title, the bag's detail header, the equipment list and a bar under the item on the ground (`MorganaWorldRenderer`).
- Tooltip (`ItemInfo.tip`, `ItemInfo.describe`): "Rare melee weapon", one line per affix (`+3 STR`, `+12 max HP`, `+3 damage`, `+2 armour`), "Item level N".
- Price: the item's price times `priceMultiplierPercent`; the shop's sell rate applies on top, the same arithmetic on server (`shopSellImpl`) and client (`TradeMath.sellValue`).

## Not built yet
Unidentified items, named uniques and sets with fixed affixes, rare random names, magic find, affixes on spells or status effects, rolled bonuses visible to Lua, rerolling or crafting with affixes, and an F1 editor for the `loot` section (it is authored in `game.json`, like weather zones).

## Tests
`ItemRollsTest` (core-domain: eligibility, determinism, rarity, level, slot and range rules, naming and price, worn totals, `addStack`, monster drops with and without loot rules, rolls kept through pickup, equip, unequip, bank and trade, sell price, floor loot at the floor's item level, validator issues), `ItemRollsPacketTest` (shared-protocol: wire round trip, old payloads), `BagModelsTest` (client tooltip and sell value), and `RoguelikeReferenceGameTest` (Lanternfall's rules validate, item level rises with depth, uniques stay fixed).

Related: [combat and loot](/systems/combat.md), [shops, bank and crafting](/systems/economy.md), [Lanternfall](/content/roguelike_game.md), [game definition](/domain/game_definition.md).

**Sockets on drops**: `RarityDefinition.socketChanceBasisPoints` is the chance (10000 = always) that a dropped piece of that rarity carries one empty socket, rolled by `ItemRoller.rollDropSockets` after the rarity at monster drops (`CombatSystem`) and generated dungeon floors (`InstanceSystem`), and only when `gearUpgrade.maxSockets` is at least 1. It spends no random number when the chance is 0. Quality raises (`gearUpgrade.quality`) can add another socket. Test: `ItemRollsTest`.
