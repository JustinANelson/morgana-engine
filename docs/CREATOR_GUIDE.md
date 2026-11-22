# Morgana ORPG Creator Guide

Welcome to the **Morgana ORPG Engine**! This guide walks you through building, customizing, and distributing your own independent 2D Online RPG without writing or modifying a single line of engine Kotlin code.

---

## 1. Quickstart: Create Your Game in 30 Seconds

The engine provides a built-in scaffolding tool that generates a complete, valid, out-of-the-box playable game project template.

Run the following command from the Morgana repository root:

```powershell
.\gradlew.bat :server-app:newGame -PcontentArgs="--name 'Shadows of Eldoria' --dir games/eldoria"
```

This scaffolds a decoupled game project under `games/eldoria/` with:
- `game.json` — Engine API version, attribute definitions, vitals (HP, MP), leveling curve, and combat formulas.
- `data/` — Minimal starter content (Map 1 Town Square, Adventurer class, Bronze Sword item, Town Guide NPC).
- `scripts/main.lua` — Hot-reloadable Lua script with starter slash command (`/hello`) and custom action (`scout`).
- `README.md` — Project documentation.

---

## 2. Running Your Game

### Step 2.1: Start the Authoritative Dedicated Server
Start the server pointing to your custom game directory:

```powershell
.\gradlew.bat :server-app:run --args="--game games/eldoria"
```

The server automatically compiles and validates your `game.json`, indexes your `data/` collections, starts the Lua script sandbox, and begins authoritative simulation at 20 ticks per second (50ms).

### Step 2.2: Launch the Desktop Client
In a separate terminal window, launch the LibGDX desktop client:

```powershell
.\gradlew.bat :client-desktop:run
```

Click **Register**, create an account (e.g. `Hero`), select the **Adventurer** class, and enter the world. You will spawn in the Town Square of *Shadows of Eldoria*!

Try typing `/hello` in chat or using the `scout` action.

---

## 3. Customizing Your Game Rules (`game.json`)

The entire mechanical rulebook of your ORPG is defined in `game.json`. Open `games/eldoria/game.json` in any text editor.

### 3.1 Changing Attributes & Stats
You are never locked into classic RPG stats. You can define any stats you want:

```json
"attributes": [
    { "id": "might", "name": "Might", "short": "MGT", "base": 10, "allocatable": true },
    { "id": "grace", "name": "Grace", "short": "GRC", "base": 8, "allocatable": true },
    { "id": "focus", "name": "Focus", "short": "FOC", "base": 6, "allocatable": true }
]
```

### 3.2 Defining Custom Resource Pools (Vitals)
Define custom pools like Health, Energy, or Sanity. Exactly one vital must have `"role": "life"` (when it reaches 0, the player dies):

```json
"vitals": [
    {
        "id": "hp",
        "name": "Health",
        "short": "HP",
        "color": "#dc2626",
        "role": "life",
        "max": "80 + level * 20 + might * 5 + bonus",
        "regen": { "everyTicks": 40, "amount": "max(1, might / 3)" }
    },
    {
        "id": "energy",
        "name": "Energy",
        "short": "EN",
        "color": "#10b981",
        "max": "50 + grace * 10 + bonus",
        "regen": { "everyTicks": 20, "amount": "max(2, grace / 2)" }
    }
]
```

### 3.3 Custom Combat Algorithms
Morgana evaluates combat using pure integer arithmetic expressions:

```json
"combat": {
    "melee": "max(1, a.might * 3 + weapon - d.grace)",
    "ranged": "max(1, a.grace * 3 + weapon + ammo - d.grace)",
    "spell": "raw = max(1, basePower + (scales ? a.focus * 3 : 0)); heals ? raw : max(1, raw - d.focus / 2)",
    "critChanceBp": "clamp(a.grace * 60, 500, 5000)",
    "dodgeChanceBp": "clamp(d.grace * 40, 0, 3500)"
}
```

---

## 4. Authoring Content: F1 Live Editor vs. JSON

Morgana enforces a **Data First, GUI Optional** philosophy:

### Option A: The In-Game F1 Editor Suite
1. Grant your account Owner / Admin permissions:
   ```powershell
   .\gradlew.bat :server-app:grantOwner -PcontentArgs="--user Hero --dir server-data"
   ```
2. In the desktop client, press **F1** to open the Admin GUI.
3. Access real-time graphical editors:
   - **Map Editor**: Paint ground, mask, fringe, roof tiles, block tiles, warp portals, directional walls, and light sources.
   - **Item Editor**: Create weapons, armors, consumables, tools, and spell scrolls.
   - **NPC Editor**: Configure sprites, aggro ranges, drops, and AI behaviors.
   - **Class Editor**: Set starter attributes, starting locations, and equipment.
   - **Quest & Event Editor**: Author multi-page NPC dialogues and branching storylines.
4. Saving in the F1 editor immediately writes schema-versioned JSON to `games/eldoria/data/`.

### Option B: Direct JSON Editing & Validation
You can also edit any JSON file directly under `games/eldoria/data/` or use external tools and version control (Git).

Before running, validate your files at any time:
```powershell
.\gradlew.bat :server-app:validateContent -PcontentArgs="--game games/eldoria"
```

---

## 5. Adding Scripted Mechanics with Lua

Place Lua scripts in `games/eldoria/scripts/`.

### Example: Teleport Scroll Action
```lua
-- scripts/spells.lua

register.action("town_portal", function(char_id, params, world)
    local player = world.get_player(char_id)
    if player and player.vitals.hp and player.vitals.hp.cur > 0 then
        world.floating_text(player.map_id, player.x, player.y, "*warping*", "spell", "#38BDF8")
        world.warp_player(char_id, 1, 10, 10)
        world.send_message(char_id, "You are safely returned to Town Square.", "#38BDF8")
    end
end)
```

### Script Permissions & Sandboxing
To ensure community scripts or downloaded mods cannot perform unauthorized mutations, specify permitted `capabilities` in `game.json`:

```json
"capabilities": ["damage", "heal", "message", "visual", "teleport"]
```

If a script tries to execute an unpermitted mutation (e.g. `world.give_item` when `"inventory"` is not in `capabilities`), the engine simulation safely rejects the effect.

---

## 6. Validating, Exporting & Distributing Your World

### Validate All Content & Scripts
```powershell
.\gradlew.bat :server-app:validateContent -PcontentArgs="--game games/eldoria"
.\gradlew.bat :server-app:validateScripts -PcontentArgs="--game games/eldoria"
```

### Browse Your Game as a Wiki
Build a cross-linked, searchable wiki of everything in your game (items with who drops and sells them, monsters with stats and loot, maps with pictures, quests, recipes) plus a Balance report that works out experience per hit point, loot value, the experience curve, crafting margins and shop exploits, and flags what stands out:
```powershell
.\gradlew.bat :server-app:gameWiki "-PcontentArgs=--game games/eldoria"
```
Open `build/game-wiki/<game id>/index.html`. A running server with a web port also serves it to players at `http://<server>:<port>/wiki/` (turn it off with `"wiki": { "enabled": false }` in `server.json`).

### Export a World Pack
You can package your world into a compressed, distributable world pack:
```powershell
.\gradlew.bat :server-app:exportWorldPack -PcontentArgs="--dir games/eldoria/data --out packs/eldoria-v1.zip --name 'Shadows of Eldoria' --author 'YourName'"
```

Other creators can then import your pack with:
```powershell
.\gradlew.bat :server-app:importWorldPack -PcontentArgs="--file packs/eldoria-v1.zip --mode merge"
```

---

## 7. Next Steps

- Consult [API Reference](API_REFERENCE.md) for full `game.json` schemas, expression syntax, and Lua world functions.
- Check [Plugin Architecture](PLUGIN_ARCHITECTURE.md) for details on engine extensibility.
- Start from `games/blank` (or run `newGame`) for a minimal project; the old reference games were removed on 2026-11-13.

