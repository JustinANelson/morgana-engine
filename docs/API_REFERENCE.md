# Morgana ORPG Engine API Reference

This document is the authoritative API reference for custom game projects running on the **Morgana ORPG Engine** (Engine API Version `1`).

---

## 1. `game.json` Specification

Every game project must contain a `game.json` file in its root directory. This document defines the mathematical foundation, stats, vitals, leveling curves, combat algorithms, and script permissions for the game.

### Top-Level Fields

| Field | Type | Description |
|---|---|---|
| `schemaVersion` | `Int` | Document format version. Currently `1`. |
| `engineApi` | `String` | Engine API compatibility contract. E.g. `"1"`, `"1.0"`, or `"^1.0"`. |
| `id` | `String` | Unique lowercase identifier (e.g. `"aethelgard"`). Matches `^[a-z][a-z0-9_]*$`. |
| `name` | `String` | Display title of the game (1 to 60 characters). |
| `capabilities` | `List<String>?` | Optional whitelist of permitted script mutations (see [Script Capabilities](#script-capabilities)). If omitted, all effects are allowed. |
| `attributes` | `List<Attribute>` | Stats declared by the game (e.g. strength, agility). Up to 32 attributes. |
| `vitals` | `List<Vital>` | Resource pools (HP, MP, Stamina). Up to 8 vitals. Exactly one must have `role: "life"`. |
| `progression` | `Progression` | Level cap and experience progression curve. |
| `combat` | `Combat` | Mathematical formulas for physical, ranged, and magical combat. |

---

### Script Capabilities

When `capabilities` is specified in `game.json`, any script that attempts an ungranted mutation is rejected by the authoritative simulation without altering world state.

| Capability | Permitted `ScriptEffect` Types |
|---|---|
| `"damage"` | `DamagePlayer`, `DamageNpc` |
| `"heal"` | `HealPlayer`, `HealNpc` |
| `"inventory"` | `GiveItem`, `RemoveItem` |
| `"teleport"` | `WarpPlayer` |
| `"status"` | `ApplyStatus`, `RemoveStatus` |
| `"message"` | `BroadcastMessage`, `SendPlayerMessage` |
| `"visual"` | `EmitFloatingText`, `TriggerAnimation` |
| `"projectile"` | `SpawnProjectile` |
| `"progression"` | `AwardExperience` |
| `"vital"` | `ConsumeVital` |
| `"state"` | `SetState` |

---

### Attributes (`attributes`)

Each attribute defines a stat on characters:

```json
{
  "id": "str",
  "name": "Strength",
  "short": "STR",
  "base": 10,
  "floor": 1,
  "cap": 1000,
  "allocatable": true
}
```

- `id`: Lowercase identifier. Cannot collide with vital IDs or reserved words.
- `name`: Display name shown in UI and character sheets.
- `short`: Short 2-4 letter abbreviation for HUD.
- `base`: Starting base value for classes that do not override it.
- `floor`: Minimum effective value after equipment and debuffs (cannot drop below this).
- `cap`: Optional maximum effective value (defaults to engine hard cap `1,000,000`).
- `allocatable`: Boolean indicating whether players can spend unallocated stat points on this stat upon leveling up.

---

### Vitals (`vitals`)

Vitals represent depletable pools with maximums and optional regeneration:

```json
{
  "id": "hp",
  "name": "Health",
  "short": "HP",
  "color": "#dc2626",
  "role": "life",
  "max": "max(10, 80 + level * 15 + vit * 10 + str * 2 + bonus)",
  "regen": {
    "everyTicks": 40,
    "amount": "max(1, vit / 4)"
  }
}
```

- `role`: Exactly one vital must specify `"role": "life"`. When this vital reaches zero, the character dies and triggers death penalties / respawn.
- `color`: Hex color string (`#rrggbb`) used to render the bar on the HUD.
- `max`: Formula evaluated to calculate the character's maximum pool capacity. Receives `level`, `bonus` (equipment bonuses), and every declared attribute ID.
- `regen`: Optional regeneration configuration. `everyTicks` specifies frequency (20 ticks = 1 second). `amount` specifies formula to restore per interval.

---

### Progression (`progression`)

Defines max level and experience required:

```json
{
  "maxLevel": 50,
  "xpExpr": "100 * level * level"
}
```

Or an explicit table of experience requirements:

```json
{
  "maxLevel": 10,
  "xpTable": [100, 250, 500, 1000, 2000, 4000, 8000, 16000, 32000]
}
```

---

### Combat Formulas (`combat`)

Combat algorithms are integer arithmetic formulas evaluated dynamically during combat resolution:

- `melee`: Evaluated on melee attacks. Variables: `a.<stat>`, `d.<stat>`, `weapon`, `armor`, `crit`.
- `ranged`: Evaluated on bow/ranged attacks. Variables: `a.<stat>`, `d.<stat>`, `weapon`, `ammo`, `armor`, `crit`.
- `spell`: Evaluated on spells. Variables: `a.<stat>`, `d.<stat>`, `basePower`, `scales`, `heals`.
- `critChanceBp`: Basis points (10000 = 100%, 100 = 1%) for critical strike chance. Variables: `a.<stat>`.
- `dodgeChanceBp`: Basis points for defender's dodge / evasion chance. Variables: `d.<stat>`.

---

## 2. Expression Language Reference

All formulas in `game.json` are written in Morgana's pure, deterministic integer expression language. Floating point math is strictly forbidden to preserve golden replay determinism across all platforms.

### Supported Operators
- Binary arithmetic: `+`, `-`, `*`, `/`, `%` (integer division truncates towards zero)
- Comparisons: `<`, `<=`, `>`, `>=`, `==`, `!=` (evaluate to `1` for true, `0` for false)
- Logical operators: `&&`, `||`, `!`
- Conditional: `cond ? exprIfTrue : exprIfFalse`

### Built-in Functions
- `min(a, b)`: Minimum of two integers.
- `max(a, b)`: Maximum of two integers.
- `clamp(val, min, max)`: Clamps `val` between `min` and `max`.
- `abs(x)`: Absolute value.
- `mulDiv(a, b, c)`: Computes `(a * b) / c` using 128-bit intermediate precision without intermediate overflow.
- `pow(base, exp)`: Integer exponentiation (`exp >= 0`).
- `isqrt(x)`: Integer square root (`floor(sqrt(x))`).

---

## 3. Lua Scripting Host API Reference

Scripts are located in the `scripts/` directory of the active game. Morgana embeds a sandboxed Luak Lua 5.2 runtime with a 10,000 instruction budget per invocation and no OS or filesystem access.

### Global Registries

#### `register.command(name, description, minAccessLevel, handler)`
Registers a custom slash command:
```lua
register.command("spawn_slime", "Summons a slime helper", 0, function(player, args, world)
    world.floating_text(player.map_id, player.x, player.y, "*slime summoned*", "info", "#22C55E")
    return true
end)
```
- `minAccessLevel`: Integer access rank (`0` = Player, `1` = Monitor, `2` = Mapper, `3` = Developer, `4` = Admin, `5` = Owner).
- `player`: Snapshot table of the invoking character (`{ id, name, map_id, x, y, level, attributes, vitals }`).
- `args`: List of command arguments split on whitespace.
- `world`: World snapshot and effect dispatch functions.

#### `register.action(actionId, handler)`
Registers a client custom action (bound to hotbars, buttons, or keybinds):
```lua
register.action("scout", function(characterId, params, world)
    local player = world.get_player(characterId)
    if player then
        world.floating_text(player.map_id, player.x, player.y, "*scouting*", "action", "#FACC15")
    end
    world.send_message(characterId, "You scout your surroundings.", "#94A3B8")
end)
```

#### `register.combat(table)`
Overrides combat resolution routines directly from Lua:
```lua
register.combat({
    melee = function(attacker, defender, weapon, armor, crit)
        local base = attacker.stats.str * 2 + weapon - armor
        return crit and math.floor(base * 1.5) or math.max(1, base)
    end
})
```

---

### Plugins: taking part in the engine's systems

A **plugin** is a Lua module in the game's `scripts/` folder that changes how one of the engine's systems works in this game: who may use a spell, who may fight whom, how monsters behave. Plugins are sandboxed, can ship inside a template or a world pack, and name the systems they need. The engine's own systems (promotion, rebirth, threat, boss encounters and so on) give their opinion first; a plugin's hooks come after them. Every hook below is optional, and a hook that errors, runs out of budget or returns the wrong type counts as "no opinion" (five failures in a row switch the whole module off, and the server log says which).

#### `register.plugin{ id, name, version, description, requires, replaces }`
Declares the plugin. `id` is required (2 to 32 characters: lowercase letters, digits, `-` and `_`, starting with a letter). `requires` is a list of system names (the keys of `modules` in `server.json`, such as `"parties"`, `"instancing"`, `"market"`); while any of them is switched off, **all of this module's hooks below are quiet**. `replaces` is a list of **engine systems** (the same names, but only the engine's own: `promotion`, `rebirth`, `arena`, `guildWar`, `guildBank`, `pets`, `stalls`, `durability`, `gearUpgrades`, `respec`, `threat`, `bossEncounters`) that the plugin takes over: while the plugin runs, the engine treats them as switched off, so your hooks are the only rule. The system comes back by itself if the plugin stops running (a system it `requires` is off, or the module was switched off after repeated errors). A module that never calls `register.plugin` simply has no requirements and replaces nothing. A malformed declaration, or a `requires` name that is not a system, stops the module loading with a message.
```lua
register.plugin{ id = "ogre-rage", name = "Ogre Rage", requires = { "instancing" } }

-- or take over a whole system: no engine threat table, the plugin's own monster rules instead
register.plugin{ id = "my-threat", replaces = { "threat" } }
```

#### `register.requirement(function(subject, player, world))`
Can refuse a character a spell or item. Return the reason as a string to refuse (the player sees it), or nothing. `subject` is `{ kind = "spell" | "item", id, name, level_req }`; `player` is the usual snapshot. It is asked when a spell is cast, an item is used or worn, a spell scroll is read, a gear upgrade leads to a higher item, and when an event teaches a spell, after the engine's own checks. Pure query, formula budget.
```lua
register.requirement(function(subject, player, world)
    if subject.kind == "spell" and subject.id == 7 and player.level < 20 then
        return subject.name .. " needs level 20."
    end
end)
```

#### `register.may_fight(function(attacker, defender, world))`
Decides who may hurt whom between players. Return `true` or `false`, or nothing to let the normal PK and map rules decide. The engine's arena and Guild War give their ruling first; the first ruling wins. Pure query, formula budget.
```lua
register.may_fight(function(attacker, defender, world)
    if defender.level < 5 then return false end   -- no hunting beginners
end)
```

#### `register.monster{ npcs, holds_still, variant, retarget, step, may_be_elite, kill_lockout }`
Changes how monsters think and fight. Give at least one function. `npcs` is a list of monster definition ids the hook applies to; leave it out and the hook applies to every monster, which costs a Lua call per monster per tick, so name the monsters you change. The `npc` snapshot has `instance_id, npc_id, name, map_id, x, y, direction, is_alive, vitals, attributes`, plus `target_id` (who it fights now, or `nil`) and `threat` (threat by character id).

| Function | Returns | Meaning |
| :--- | :--- | :--- |
| `holds_still(npc, world)` | boolean | `true` and the monster does nothing this tick |
| `variant(npc, world)` | `{ attack_percent = 150, move_speed_percent = 80 }` or nothing | multipliers folded into the monster's strength (each field defaults to 100, kept between 0 and 1000) |
| `retarget(npc, world)` | a player id or nothing | who the monster should fight now; ignored unless that player is alive on its map |
| `step(npc, world)` | nothing (it causes effects) | a scripted turn each tick, before the monster thinks; may call the `world.*` effect functions (damage, heal, messages, projectiles, state), applied inline and limited by the game's script capabilities; action budget |
| `may_be_elite(npc_id, world)` | boolean | `false` and this monster never becomes an elite variant (one `false` is enough) |
| `kill_lockout(npc_id, world)` | ticks or nothing | how long a kill locks its killers out of its rewards (the longest asked wins) |

The functions other than `step` are pure queries under the formula budget: anything they try to cause is dropped. Hooks run in the order the modules loaded.
```lua
register.plugin{ id = "ogre-rage" }
register.monster{
    npcs = { 10 },
    variant = function(npc, world)
        local hp = npc.vitals.hp
        if hp.cur * 2 < hp.max then return { attack_percent = 150, move_speed_percent = 130 } end
    end,
    step = function(npc, world)
        if npc.target_id and world.tick % 100 == 0 then
            world.floating_text(npc.map_id, npc.x, npc.y, "GRRR", "script", "#F87171")
        end
    end,
}
```

#### `register.tick(function(world))` or `register.tick{ stage, every, run }`
Does something on a schedule. The table form takes `stage` (`"after_npcs"`, or `"after_events"` which is the default and the shorthand's stage), `every` (a period in ticks from 1 to 72000, default 1) and `run`. It runs on ticks that are a multiple of `every`, and may call the `world.*` effect functions (applied inline, limited by the game's script capabilities, action budget). Use `world.get_state` and `world.set_state` to remember things between runs; a Lua call has no memory of its own.
```lua
register.tick{ every = 200, run = function(world)
    world.broadcast("The lanterns flicker.")
end }
```

#### `register.item_use{ items, use }`
Gives items a use of their own. `items` is a list of item ids (leave it out to be asked about every item). `use(item, player, slot, world)` gets `item` as `{ id, name, type, level_req }`, the player snapshot and the bag `slot` (counting from 1). Return `true` to take the item over: the effects you caused are applied and the engine does not use the item (so remove it yourself with `world.remove_item` if it should be used up). Return nothing or `false` and the item is used as usual, and the effects are dropped. Action budget.
```lua
register.item_use{ items = { 9 }, use = function(item, player, slot, world)
    world.heal_player(player.id, 25)
    world.remove_item(player.id, item.id, 1)
    return true
end }
```

#### `register.action_gate{ activities, blocks }`
Stops a character doing something for now. `activities` is a list from `"move"`, `"attack"`, `"cast"`, `"use_item"`, `"trade"` (leave it out for all of them, which asks Lua on every step, so name the ones you need). `blocks(player, activity, world)` returns `true` to stop them. Pure query, formula budget. The engine's own gates (an open stall) are asked first.
```lua
register.action_gate{ activities = { "cast" }, blocks = function(player, activity, world)
    return world.clock ~= nil and world.clock.phase == "night" and player.level < 10
end }
```

#### `register.on_interrupt(function(player, world))`
Told when a character is pulled away from what they were doing: they die, or an arena match starts. Use it to wind down your own state. It may cause effects, but any that would change **that character** (damage, healing, items, warp, status, experience, vitals) are dropped, because the engine is part-way through changing them; messages to them, state, and effects on other characters and monsters are applied. Action budget.

#### `register.combat_observer{ after_attack, after_struck }`
Reacts to the blows of a fight; give either or both. `after_attack(attacker, world)` runs when a player lands a hit with a weapon (an unarmed hit does not count). `after_struck(victim, world)` runs when a player has taken a hit. Both may cause effects; as with `on_interrupt`, effects that would change the struck character are dropped from `after_struck` (react on others, on messages and on state). Action budget.

#### `register.on_kill(function(killer, xp, world))`
Told when a player kills a monster and how much experience it earned them. Effects may change the killer (give an item, award experience, heal). Action budget.
```lua
register.on_kill(function(killer, xp, world)
    if xp >= 100 then world.send_message(killer.id, "A worthy foe.") end
end)
```

#### `register.schedule{ id, ids, on_warning, on_fire }`
Reacts to the scheduler (`schedules` in `game.json`, or one-offs booked with `world.schedule_once`). `id` (or a list `ids`) limits it to those schedules; omit both to hear every schedule. Give `on_warning`, `on_fire` or both; each is `function(info, world)`, may cause effects (action budget) and runs once per warning or start. `info` is `{ id, name, phase ("warning" or "fire"), seconds_before, occurrence_ms, recurring, data }`; `data` holds what a one-off was booked with.
```lua
register.schedule{ id = "cleansing",
  on_fire = function(info, world)
    world.broadcast("The Cleansing sweeps the Wildlands.")
    world.schedule_once("aftermath", world.now_ms + 3600 * 1000, { name = "Aftermath", warnings = { 60 }, data = { arc = "7" } })
  end }
```

#### `register.object_gate(function(info, world))` or `register.object_gate{ kinds, check }`
Rules on what may be built (needs the `worldState` system and `worldObjects` kinds in `game.json`). Asked for every placement after the engine's own checks (a free tile, a known kind, room on the map). `info` is `{ kind, map_id, x, y, owner_id }` (`owner_id` is absent when the game places it). Return `false` to refuse, optionally `false, "Why."` (shown to the player; the default is "You cannot build that here."); return nothing to allow. `kinds` limits it to those kinds. A pure query with the formula budget: it reads the world (`world.objects_near`, `world.get_map`) but causes no effects.
```lua
register.object_gate{ kinds = { "wall" }, check = function(info, world)
  for _, o in ipairs(world.objects_near(info.map_id, info.x, info.y, 6)) do
    if o.owner_id ~= info.owner_id then return false, "Too close to another base." end
  end
end }
```

#### `register.object_use{ kinds, gate, use }`
Rules for using a placed object: what happens when a character faces it and interacts (needs `worldState`). `gate(player, obj, world)` is a pure query (formula budget): return `false, "Why."` to refuse (shown to the player; default "You cannot use that."). `use(player, obj, world)` may cause effects (`world.set_object_data`, `give_item`...) and, when present, settles the use so the tile's own interaction does not run. Give a gate, a use or both; `kinds` limits it to those kinds. `obj` is the same table as `world.get_object` returns.
```lua
register.object_use{ kinds = { "chest" },
  gate = function(player, obj, world) if obj.data.damaged == "yes" then return false, "It is too damaged to use." end end,
  use = function(player, obj, world) world.set_object_data(obj.id, { last_user = player.id }) end }
```

#### `register.warp(function(player, warp, world))`
Rules on warps: every way a player is moved to a map (warp tiles, warp spells and scrolls, the event warp step, a script warp) asks this first. `warp` is `{ map_id, name, x, y, instanced }`. Return `false` to refuse the warp (optionally `false, "Why."` to tell the player), return a map id to send the player to **that map instead** (same tile; the new target is routed again, so a redirect to an instanced map gets its private copy), or return nothing to leave the warp alone. Pure query, formula budget. A warp hook is asked before the engine's own instancing, so it sees the warp as it was made. A plugin that wants a different rule for instanced maps replaces the system: `register.plugin{ id = "shared-crypts", replaces = { "instancing" } }` makes an instanced map one shared map.
```lua
register.warp(function(player, warp, world)
    if warp.map_id == 12 and player.level < 10 then
        return false, "The Deep Crypt needs level 10."
    end
end)
```

#### Which Lua call reaches which engine seam
| Seam | Lua |
| :--- | :--- |
| requirement gates | `register.requirement` |
| combat arbiter (who may fight) | `register.may_fight` |
| monster behaviour | `register.monster` |
| tick participants | `register.tick` |
| item use | `register.item_use` |
| action gates | `register.action_gate` |
| activity interrupters | `register.on_interrupt` |
| combat observers | `register.combat_observer` |
| kill observers | `register.on_kill` |
| schedule handlers | `register.schedule` |
| world object gates | `register.object_gate` |
| world object interaction | `register.object_use` |
| warp routers | `register.warp` |
| chat commands | `register.command` |
| event commands | `register.event_command` (and `register.event_condition`) |

Other registrations that already took part in the engine before plugins: `register.command` (slash commands), `register.event_command` and `register.event_condition` (event pages), `register.action` (client actions), `register.formula` (override a game formula) and `register.combat` (replace the combat module). Together with the three above they cover chat, events, rules and combat from Lua. Every seam is now open to Lua (see the table above). Every engine system can be switched off from `server.json`, a template or the F1 Systems panel, and a plugin can replace it with `replaces`.

---

### World Query Functions

- `world.get_player(characterId)`: Returns snapshot table `{ id, name, map_id, tile_x, tile_y, vitals, stats, level }` or `nil`.
- `world.get_npc(mapId, instanceId)`: Returns snapshot table `{ id, npc_id, name, tile_x, tile_y, vitals }` or `nil`.
- `world.get_map(mapId)`: Returns snapshot table `{ id, name, width, height, moral }` or `nil`.
- `world.random(min, max)`: Returns a deterministic pseudo-random integer between `min` and `max` (inclusive) using the simulation's tick seed.

---

### World Mutation Functions

These functions emit declarative `ScriptEffect` mutations that are validated against the game's `capabilities` and applied deterministically:

| Function | Parameters | Description |
|---|---|---|
| `world.damage_player` | `(targetId, amount, [vitalId], [sourceId])` | Inflicts damage on a player. |
| `world.damage_npc` | `(mapId, npcInstanceId, amount, [vitalId], [sourcePlayerId])` | Inflicts damage on an NPC. |
| `world.heal_player` | `(targetId, amount, [vitalId])` | Restores vital points to a player. |
| `world.heal_npc` | `(mapId, npcInstanceId, amount, [vitalId])` | Restores vital points to an NPC. |
| `world.give_item` | `(playerId, itemId, [amount])` | Adds item(s) to a player's inventory. |
| `world.remove_item` | `(playerId, itemId, [amount])` | Removes item(s) from a player's inventory. |
| `world.warp_player` | `(playerId, mapId, x, y)` | Teleports player to destination coordinate. |
| `world.broadcast` | `(message, [mapId], [colorHex])` | Broadcasts chat message (to entire game or map). |
| `world.send_message` | `(playerId, message, [colorHex])` | Sends private chat message to a player. |
| `world.floating_text` | `(mapId, x, y, text, [category], [colorHex])` | Spawns animated floating combat/info text. |
| `world.trigger_animation` | `(mapId, x, y, animId, [soundId])` | Spawns visual animation effect on a tile. |
| `world.spawn_projectile` | `(mapId, sourceId, isPlayer, x, y, dir, range, spriteId, dmg, speed, spellId)` | Launches physical or magical projectile. |
| `world.award_xp` | `(playerId, amount)` | Grants experience points to a player. |
| `world.consume_vital` | `(playerId, vitalId, amount)` | Deducts points from a player's vital pool. |
| `world.schedule_once` | `(id, atMs, [{ name, warnings = {seconds...}, data = {k = v}, warning_message, fire_message }])` | Books a one-off with the scheduler, `atMs` in epoch milliseconds (use `world.now_ms + ...`). Ignored if the scheduler is off, the time is not in the future, or `id` belongs to a `game.json` schedule. Needs the `state` capability. |
| `world.cancel_schedule` | `(id)` | Cancels a one-off booked with `world.schedule_once`. Needs the `state` capability. |
| `world.players_on_map` | `(mapId)` | Every player on the map, each as `world.get_player` returns, by id. A query (works in any hook). |
| `world.npcs_on_map` | `(mapId)` | Every monster instance on the map (dead ones too: check `is_alive`), each as `world.get_npc` returns, by instance id. A query. |
| `world.despawn_npcs` | `(mapId, [npcId])` | Removes the monsters of that kind (all when omitted) from the map, with no loot or experience; nothing respawns them until `world.reset_map` or the map is swapped. Clients get a fresh snapshot. Needs the `state` capability. |
| `world.reset_map` | `(mapId)` | Puts the map's monsters (at their spawn slots, full vitals), resource nodes, ground loot and projectiles back to a fresh start; players and world objects are untouched. Needs the `state` capability. |
| `world.get_object` | `(id)` | The world object with that id as `{ id, kind, map_id, x, y, owner_id, guild_id, hp, data }`, or `nil`. |
| `world.objects_near` | `(mapId, x, y, [radius])` | World objects within `radius` tiles (default 1, at most 64), nearest first, each as above. |
| `world.place_object` | `(kind, mapId, x, y, [ownerId], [data])` | Places a world object; ignored if refused. Needs the `state` capability. |
| `world.remove_object` | `(id, [reason])` | Takes a world object away (`reason` goes to observers). Needs the `state` capability. |
| `world.damage_object` | `(id, amount)` | Hurts a world object; it is destroyed at 0 HP. Needs the `state` capability. |
| `world.set_object_data` | `(id, table)` | Replaces what is kept on a world object (string, number and boolean values, kept as strings). Needs the `state` capability. |
| `world.set_state` | `(key, table)` | Stores `table` (string, number and boolean values, kept as strings) under `key` in the simulation's script state; `nil` or `{}` deletes the key. Hooks are stateless, so this is how a script remembers anything between ticks. Hashed for replays, not saved. |

### Content and State Queries (action combat)

| Function | Returns |
|---|---|
| `world.get_action_skill(id)` | The `action_skills` definition as a table (`startupTicks`, `hitboxes`, ...), or `nil`. Defaults are filled in; absent optional fields are `nil`. |
| `world.get_weapon_set(id)` | The `weapon_sets` definition (`entries`, `edges`, `inputBufferTicks`, ...), or `nil`. |
| `world.get_state(key)` | A table of the strings stored by `world.set_state`, or `nil`. Convert numbers with `tonumber`. |
| `world.state_keys(prefix)` | Sorted array of state keys starting with `prefix`. |

Players, NPCs and range-query entities also carry `px` / `py`, the centre of the character in world pixels (a tile is 32).

