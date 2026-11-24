# Agentic LLM Development Guide

This project is structured for high-velocity, high-reliability pair programming with AI coding agents (Antigravity, Claude Code, Cursor, Gemini, Windsurf).

---

## 1. Key Files for Agents
| File | Purpose |
| :--- | :--- |
| `AGENTS.md` | Primary source of truth for project mission, invariants, and commands |
| `CLAUDE.md` / `GEMINI.md` | Pointers to `@AGENTS.md` for multi-agent compatibility |
| `wiki/index.md` | Linked, code-anchored knowledge base (read first for non-trivial work; see `docs/WIKI_GUIDE.md`) |
| `docs/DESIGN.md` | Design pillars and phased roadmap (update as phases complete) |
| `docs/ARCHITECTURE.md` | Component boundaries and dependency rules |
| `.agents/skills/*/SKILL.md` | Task-specific skills discovered automatically by agents |

---

## 2. Recommended Agentic Workflow
1. **Orient**: Read `AGENTS.md`, `docs/DESIGN.md`, and the relevant `wiki/` pages (start at `wiki/index.md`) to understand current phase and architectural invariants.
2. **Search Fast**:
   - Text search: `rg "pattern" core-domain/src server-app/src client-core/src`
   - File search: `fd "\.kt$" core-domain server-app client-core`
   - AST search: `ast-grep --pattern 'class $NAME($$$)' core-domain/src`
3. **Implement & Test Iteratively**:
   - Add or update unit tests in `<module>/src/test/kotlin/` covering the phase requirements.
   - Run `.\gradlew.bat test` (or `.\gradlew.bat :<module>:test`) to verify behavior.
4. **Run the Quality Gate Before Completing**:
   - Run `.\gradlew.bat check` to ensure compilation, architecture/content checks, wiki lint, and tests all pass.
5. **Update Roadmap & Wiki**:
   - Move completed items from **Next** to **Done** in `docs/DESIGN.md`, and update affected pages in `wiki/`.

---

## 3. Useful CLI Utilities
When available on `PATH` (e.g. `~/.local/bin` or standard Unix shells), these tools speed up navigation and debugging:
- **Search & JSON**: `rg`, `fd`, `ast-grep`, `jq`, `tree`
- **Process Management**:
  - `pgrep -af java` — list running Java/Gradle processes with full command lines
  - `pkill -ef <pattern>` — kill processes matching command-line pattern
  - `killall <name>` — kill processes by executable name
- **Port & Network Inspection**:
  - `lsof -i :7001` — show processes listening or connected on port 7001
  - `lsof -ti :7001` — output only PIDs on port 7001
  - `fuser -k 7001/tcp` — kill process bound to TCP port 7001
- **Unix Stream Utilities**:
  - `grep`, `sed`, `awk`, `head`, `tail`, `wc`, `xargs`, `touch`, `which`, `patch`, `cut`, `tr`, `uniq`, `tee`, `realpath`

---

## 4. Authoring World Content as Files (Data-Driven Workflow)

All world content is plain JSON on disk: `games/<game-id>/data/<collection>/<prefix>_<id:3>.json` (or `server-data/world/<collection>/<prefix>_<id:3>.json` in the legacy seeded layout, e.g. `items/item_007.json`, `maps/map_001.json`). The `F1` in-game editor and file edits are equal citizens and share one
pipeline: **validate → persist → hot-reload → broadcast**.

| Collection | Directory | File name |
| :--- | :--- | :--- |
| Maps / Classes / Items / NPCs / Spells | `maps` `classes` `items` `npcs` `spells` | `map_001.json` `class_001.json` `item_001.json` `npc_001.json` `spell_001.json` |
| Shops / Resources / Recipes / Quests / Events / Animations | `shops` `resources` `recipes` `quests` `events` `animations` | `shop_001.json` `resource_001.json` `recipe_001.json` `quest_001.json` `event_001.json` `anim_001.json` |

**Agent loop**
1. Content lives in a game folder (`games/blank` by default); `.\gradlew.bat :server-app:seedWorld` seeds `server-data/world` with the same blank content if you use that layout.
2. Read a sample file of the type you need, and its JSON Schema in `docs/schemas/<collection>.schema.json`
   (regenerate with `.\gradlew.bat :server-app:exportContentSchemas`; a test fails if they go stale).
3. Create or edit files. Keep `id` equal to the number in the file name. `0` means "none" for optional references.
   Polymorphic values (tile attributes, event commands) carry a `"_type"` discriminator (see schema `oneOf`).
4. `.\gradlew.bat :server-app:validateContent` — prints one line per problem: `SEVERITY file/pointer [code] message`
   and exits 1 on errors. Add `-PcontentArgs="--json"` for machine-readable output.
5. A running server hot-reloads on save (or run `/reload` in-game as Owner). If validation fails nothing is applied and the
   errors are logged; fix and save again.

**Validation guarantees**: schema version, id/name sanity, numeric ranges, enum values, map bounds (warps, spawns, events),
and every cross-reference (items in shops/drops/recipes/quests, spells, NPCs, shops, events, animations, neighbor maps).
Unknown field names (typos) are reported as `unknown_field` warnings. `F1` saves are rejected with the same messages.

**Gotchas**: Links between maps (warps, neighbors) may dangle temporarily (warning `missing_map_link`); every other reference must exist. Owner access comes from the owner policy, not from a username (`wiki/server/owner_policy.md`). Maps edited in Tiled keep full attribute data in the `morganaAttributes` property (`wiki/content/tiled_conversion.md`). Maps removed from disk stay loaded until restart. A map's `revision` is bumped automatically on reload.
Create referenced documents before the ones that reference them (e.g. an NPC before the map that spawns it).

## 5. Authoring with a running server (hot loading)
Start the server on your game (`.\gradlew.bat :server-app:run` with the game's path) and a client, then write files; the running world follows within about a second.

- **Content** (`<game>/data/<collection>/<name>_NNN.json`, including maps): write or edit the file. Every file that validates is applied; one that does not keeps its previous version (a new bad file is simply left out). Write whole files, in place; a half-written file is harmless and is picked up when complete.
- **Scripts** (`<game>/scripts/*.lua`): saved scripts are reloaded with the content.
- **Art** (`<game>/assets/tilesets/<id>.png`, `sprites/<id>.png`, ...): new and changed files are pushed to connected clients with their PNG, so a new map and its tileset appear together.
- **`game.json`**: read at start. A change is noted and needs a restart.
- **Read the answer** in `<game>/.reload-status.json` after each change: `applied`, `changed` (`map#4`), `rejected` (per file: the file, whether the previous version was kept, and each error with its JSON pointer, code and message), `warnings`, `assetsUpdated`, `assetErrors`, `restartRequired`. Fix what `rejected` lists and save again; the status is rewritten on every reload.
- A map you delete is unloaded and its players go back to the start; a map you change keeps its monsters and resource nodes where their spawn slots did not change, and moves anyone standing in a new wall. `/reload` (owner) forces a reload.

