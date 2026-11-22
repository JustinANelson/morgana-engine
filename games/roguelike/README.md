# Ashen Keep — Reference Dungeon Crawler & Roguelike

**Ashen Keep** (`games/roguelike`) is the reference co-op dungeon crawler and roguelike template shipped with Morgana ORPG. It uses the `:tilegen` Halcyon Reach art pipeline (`PavingGenerator`, `BuildingGenerator`, `PlazaGenerator`, `GroundDetailGenerator`, `PropGenerator`, and `PixelStyle.MORGANA` 32×48 paperdoll sprites) so every building, courtyard stone, dungeon wall, monster, class body, worn gear overlay, item icon, projectile, and spell effect is self-contained inside `games/roguelike/assets/`.

## World Layout
- **Map 1 — Ashen Keep** (36×26, safe multiplayer hub): A cobblestone fortress courtyard with a central healing fountain, Forgemaster's Smithy, Apothecary & Curio Shop, Keep Hall (shared stash bank), Commander Vael (quests & run guide), Delve Warden Orin (Normal / Hard / Nightmare difficulty), and the northern stone archway portal into the crypt.
- **Map 2 — The Sunken Crypt (Floor 1)** (44×34, instanced procedural BSP dungeon): Generated fresh for each solo delver or party, featuring 5 crypt enemy types, elite variants (Frenzied, Champion, Ashen), room features (Wayside Shrine and Wandering Peddler), Diablo-style rolled loot rarities and affixes, and **Malakor, the Ashen Warden** (a 3-phase scripted boss) guarding the exit back to Ashen Keep.

## Classes & Action Combat
- **Vanguard** (melee/shield): 3-hit sword combo (`LMB`), Shield Bash (`RMB`), Dodge Roll (`Space`), and Guard + Riposte (`Q`).
- **Ranger** (bow/arrows): Quick Shot → Twin Shot (`LMB`), Piercing Arrow (`RMB`), and Backstep (`Space`).
- **Pyromancer** (staff/spells): Flame Lash → Ember Burst (`LMB`), Fire Wave (`RMB`), Blink (`Space`), plus Fireball and Mending Light.
- Every class knows **Rekindle** to revive fallen party ghosts mid-run.

## Running
```powershell
.\gradlew.bat :server-app:run --args="--game games/roguelike"
```
Or pick **Ashen Keep** in the desktop client's **New Realm Wizard**.
