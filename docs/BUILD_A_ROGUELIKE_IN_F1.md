# Build a co-op roguelike in the F1 editor

This guide takes you from an empty realm to a hub-and-dungeon roguelike (classes, loot, generated floors, bosses, difficulty
tiers, revives) using only the in-game `F1` editors. No Kotlin, no Lua, no hand-edited JSON. The finished example is
**Ashen Keep** (`games/roguelike`): pick it in the New Realm Wizard to look at and take apart everything described here.

**What you need**: the Morgana SDK (or this repository) and Java 17 or newer. New to the editors? Do
[Make your first game in 15 minutes](GETTING_STARTED.md) first; this guide assumes you know how to paint a map and save an
NPC.

How to read this guide: **bold** is a panel, button or field; *italic* is something you choose or type; `code` is a key.

## 0. Start the realm and open the editors

1. **Start the desktop client**—from the **repository root** run `.\gradlew.bat :client-desktop:run` (macOS/Linux: `./gradlew :client-desktop:run`), or from an **extracted SDK zip** (built into `build/distributables/` via `.\gradlew.bat distributables`) run `bin\morgana-client.bat` (`bin/morgana-client` on macOS/Linux). Open **Local Worlds**, press **New Realm Wizard...**, name it, pick *Blank Game* (or *Ashen Keep* to start from the finished game) and create it.
2. Select the realm and press **Play**. The first account made on this computer is the realm's **Owner**. Only the Owner may
   change the game's rules (step 1); mappers may edit everything else.
3. Press `F1`. Panels are in the **Editors** menu in three groups: **Map** (Tileset, Layers, Attributes, Maps, Map
   properties, Dungeon), **Content** (Game rules, Classes, Items, NPCs, Spells, Shops, Resources, Recipes, Quests,
   Animations, Events, Common events, Systems, Action skills, Weapon sets) and **Tools** (Admin tools, Art, Help, Settings). Floating panels can be docked.

Save buttons send the change to the running server, which checks it and writes it to the game's folder. A problem list under
each form says exactly what is wrong; **Save** stays grey until it is fixed. Everything you save is a plain JSON file in
`games/<your game>/`, so you can also commit it, diff it and share it.

## 1. Game rules: stats, loot and the roguelike systems

**Editors > Game rules** (Owner only) edits the game's `game.json`. Save writes the file and the server tells you in chat;
**rule changes take effect after the server restarts** (content edits elsewhere are live). Work top to bottom:

| Section | What to do |
| :--- | :--- |
| **Game** | Name, description, tags (shown in the realm directory), bag size. A roguelike likes 30 to 40 slots. |
| **Attributes, vitals and levels** | One line per attribute (`str | Strength | STR | 10 | 1 | - | yes`) and vital. Exactly one vital is *life*. Add a `stamina` vital if you want sprint and jump. Pick a level cap and an XP formula such as `100 * level * level`. |
| **Combat** | The damage formulas (`a` is the attacker, `d` the defender): *melee*, *ranged*, *spell*, crit and dodge. Keep the module *realtime* unless you write your own combat. |
| **HUD** | Tick what the player sees. *XP orbs* is a pleasant roguelike touch. |
| **Movement, death and progression systems** | Tick **Sprint** and **Jump (dodge)** (spend stamina; jump gets you out of a boss's marked ground), **Ghost death** (a fallen player lies where they fell until a party mate revives them, `/revive` after a delay, or they are sent home; *A wiped party is sent home* ends the run for everyone on a dungeon floor when nobody is left standing), **Bestiary**, **Paragon** (XP past the level cap earns attribute points), **Gear wear**, **Rested XP**. An unticked system is removed. |
| **Dungeon runs** | Tick **Dungeon run lifecycle** to track runs on generated floors (`/run`, `/abandon`), choose **Run state mode** (*Persistent*, *Extraction*, or *Ephemeral* with *Meta-currency item ids*), set **Permadeath on death** (*Off*, *Soft-lock (Hall of the Fallen)*, or *Hard delete*), set an optional **Run time limit**, and allow custom challenge seeds via `/seed <number>`. Enable `townGate.leaveReturnPortal` in `game.json` if you want two-way town portals (`/return`). |
| **Boons and skill tree** | Define roguelike **Boons** (one per line; players roll 3 choices with `/boon offer` and `/boon pick <1-3>`, with `runOnly` boons clearing automatically when a run ends) and optionally tick **Skill tree** to define per-class talent nodes (`/skills`, `/skills learn <id>`) with prerequisites, passive bonuses, and automatic spell unlocks. |
| **Elite monsters** (under *Combat*) | One line per elite type: how often one spawns in place of an ordinary monster, and how much tougher, faster to hunt and richer it is. Its name and colour show above it. Scripted bosses are never elites. |
| **Loot** | Tick **Loot rarities and affixes**. One line per rarity, lowest first (weight decides how often it drops; *min item level* gates it) and one per affix (`ATTRIBUTE`, `VITAL_MAX`, `ATTACK_DAMAGE`, `ARMOR_DEFENSE`, `CRIT_CHANCE_BP`, `DODGE_CHANCE_BP`, `LIFE_LEECH_PERCENT`, or `MAGIC_FIND_PERCENT`). Hold `Alt` in-game to show stacked rarity-colored ground loot labels and click any label or drop to loot it. |
| **New-player tips** | Write your own first-run tips. Use `{key:INVENTORY}` so rebinding keys never leaves a tip lying. |

Rules you do not see here (guild war, nobility, arena, promotion...) are kept exactly as they are.

### Optional systems

**Editors > Systems** (Owner only) switches the realm's systems on or off: parties, guilds, trading, crafting,
projectiles, free pixel movement, instanced maps, friends, mail, the market, boss encounters, the threat table and more,
listed under their genre. Press **Roguelike and dungeon crawler** to tick the systems a roguelike uses (parties, instanced maps,
projectiles, crafting, boss encounters, the threat table...) and untick the rest (guilds, trading, the market...); you can still
change any box before you press Apply. (The New Realm Wizard has the same genre buttons in its Features step.) A roguelike needs **Parties** and
**Instanced maps** (both on by default) and usually **Projectiles** for bows and bolt spells. Apply saves `server.json` and the
server uses it at once; the panel warns about combinations that do not work (the market without mail, for example). Players see
a switched-off system disappear from their HUD the next time they sign in.

## 2. Classes

**Editors > Classes** > **New class**. Set the name, description and character sprites, the starting and per-level attributes,
points to spend per level, the start map and tile (your hub), *Starting items* (one `item amount` per line) and the spells
the class knows from the start. Tick *Equip starting gear* if you want it worn on creation.

## 3. Items, spells, shops, recipes

- **Items**: gold must be **item 1** (type *Currency*): quests and shops pay in it. Make weapons, armour, potions,
  ammunition and materials. *Rolls a rarity and affixes when dropped* makes gear random; untick it for fixed unique items
  (a boss's signature piece).
- **Spells**: player spells, and the spells your **bosses** use for their marked ground attacks (a spell's *Base power* is
  the damage; its range and targeting are not used by bosses). Use **Targeting** *Ground area (cursor)* (`GROUND_AOE`) for
  cursor-aimed AoE spells (hotbar keys quick-cast at the cursor; clicking a button shows an aim reticle), and tick **Pierces
  targets** on directional projectile spells to pass through lined-up enemies. A revive is a spell with *Revives a fallen ghost (% life)*
  above 0 and *Can be cast on an ally*; give it to every class so a party can always get a friend back up.
- **Shops** and **Recipes** turn drops into progress: sell loot, craft better gear from materials. Keep the sell rate at or
  below the buy rate or players can farm gold.

## 4. The hub

Paint the camp as an ordinary map (see the first guide). Put in it: a shop NPC, a banker, a quest giver, healing tiles, and a
*Warp* tile (**Attributes > What this tile does > Warp**) that leads to your first dungeon floor template (step 5). Make the
hub's **Rules** *Safe zone* in **Map properties**.

## 5. Dungeon floors

A dungeon floor is a map that generates itself for each party.

1. **Maps** > **New map** for each floor (a 10 x 10 map is fine: its own tiles are ignored). In **Map properties** tick
   **Instanced** and press **Apply changes**. Name floors like `The Crypt (floor 1)`; the HUD shows "Floor 1 · The Crypt".
2. Open **Dungeon** with that map loaded and tick *This map is a generated floor template*. Fill in:
   - **Dungeon floor**: size, **Layout algorithm** (*Rooms and corridors*, *Cellular caves*, *BSP catacombs*, or *Open courtyard / arena*), **Corridor width** (2 or 3 gives 2–4 player co-op parties room to pass), room counts and sizes, and a *Fixed seed* (0 for a new layout every run).
   - **Floor look**: floor, wall, stairs (and optional wall-face) tiles as `tileset x y`, the tileset number and the column and row
     of the tile on its sheet (Lanternfall's floor templates show working values), plus scattered decorations.
   - **Monsters and loot**: one `npc weight` line per monster type and how many per room; one `item min-max weight` line per
     loot pile type and how many piles; the *Item level* of gear on this floor (deeper floors, bigger rolls).
   - **Room features**: NPCs with a dialogue event (a healing shrine, a peddler, an altar) placed once in a room after the first, a minimum and maximum per floor. Make the NPC and its event in **NPCs** and **Events** first.
   - **Boss and way out**: a *Boss* NPC beside the stairs, the *Next floor* (the next template map), and on the last floor
     *Next floor 0* with an *Exit map* and tile back to the hub.
3. Press **Apply to map**, then save the map (`Ctrl+S`). Link floor 1's *Next floor* to floor 2, and so on.
4. Point the hub's warp tile at floor 1.

**Party scaling and difficulty** is in the same panel. Tick *Scale monsters to the party* to make monsters tougher for each
extra member (and optionally for the party's average level), then list tiers, easiest first:
`150 125 150 130 6 Hard` means 150% life, 125% damage, 150% XP, 130% drops, from average level 6. Do this on every floor.
To let players choose, make an NPC *Delve Warden* with a dialogue event (below) that does **Set variable** `dungeon_tier` to
0, 1 or 2 with *player* scope; whoever opens a floor sets its tier for the whole party.

## 6. Monsters and bosses

**NPCs** > **New NPC**: set the stats, behaviour, spells and drops (one `item min-max chance` line each; chance is out of
10000). For a **boss**, fill in **Boss encounter**:

- **Phases**, one line each: `life% attack% speed% adds announcement`, for example
  `60 120 100 11,11 The Colossus rattles!` (from 60% life, 120% damage, normal speed, calls two of NPC 11). The first phase
  is usually `100 100 100 -`.
- **Telegraphed attacks**, one line each: `phase spell AROUND_TARGET|AROUND_SELF radius windup cooldown warning`, for example
  `2 6 AROUND_SELF 3 40 300 Shattering stomp!`. The ground is painted red for the wind-up; whoever is still inside takes the
  spell's damage.
- **Lockout** (ticks), if a kill should lock its killers out for a while. Leave it 0 for a roguelike.

A monster with an encounter gets the boss health bar, a minimap marker and a "defeated" toast automatically.

## 7. Quests and dialogue

**Quests** and **Events** work as in the first guide. For the difficulty chooser, add a dialogue with one choice per tier and,
under each choice, a *Set variable* step (variable `dungeon_tier`, scope *player*). Give the NPC that event as its
*Dialogue event*.

## 8. Action combat

Lanternfall fights with **action combat**: left click attacks toward the cursor (click again in time to chain a combo), right
click is a heavy skill, Space dodges, holding Q guards. To use it in your game set **Combat module** to *action* in
**Game rules** (restart after saving) and author the moves in two panels. Monsters, bosses and hotbar spells keep working as
they do in any game; only the player's attack changes. See `wiki/systems/action_combat.md` for every rule.

- **Action skills**: one skill is a move. Set its timing in ticks (start-up, active, recovery, when it can be cancelled), its
  cost and cooldown, then its **Hitboxes** (one line each: when it is live, the shape, the size, how much damage, what
  reaction it causes, an optional status or a projectile), **Armour windows** (super armour or invulnerable frames), **Movement**
  (a lunge or dash) and, for a block, the **guard** fields.
- **Weapon sets**: which skills a weapon offers, which input starts each (`LMB`, `SHIFT+LMB`, `FORWARD+RMB`...), and the combo
  links between them (after skill 1, `LMB` within 8 ticks leads to skill 2, optionally only if skill 1 hit).

Each hitbox line has 21 columns, listed under the field; the easiest start is to open one of Lanternfall's skills (a slash, a
projectile shot, a roll with dodge frames, a guard) and change the numbers. Give every class a weapon set with entries for `LMB`
and `SPACE`, because those are the keys players press first; the engine picks a class's set automatically.

## 9. Test it

Use **Play** with two clients (form a party with `P`), walk through the portal, and check: the floor generates, monsters
and loot are right, the boss telegraphs, a party mate's revive works, and the exit sends you home. `.\gradlew.bat
:server-app:validateContent "-PcontentArgs=--game games/<your game>"` checks the whole game from the command line.

## What is not in the editors

Every kind of game content and every game rule can now be authored in the editors. Two limits remain:

- **Rule changes need a restart.** Game rules are saved to `game.json` but the running server keeps the rules it started with.
  (Content edits and system switches apply at once.)
- **Settings that are not game content**: the network, TLS, limits and admin panel settings in `server.json`, and the world's
  weather zones inside `game.json`, are for the person running the server and are edited as files.
- **Missing engine features, not missing editors:** persistent cross-run **leaderboards**, handcrafted **room templates**, and **breakable props / one-click chests** are planned next; see `wiki/reference/roadmap_status.md`.

The list is kept current in `wiki/issues/f1_editor_authoring_gaps.md`.
