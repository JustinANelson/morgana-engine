# Engine and Game Separation, Scripting and Plugins

**Status (2026-11-14):** the engine/game split, data-defined stats, the sandboxed Lua host, replaceable combat and the open-engine tooling are **built** (phases 0 to 5 below). What remains is listed in section 6. The direction is recorded in [ADR 0013](../wiki/decisions/0013_engine_game_split_and_lua_scripting.md); the file format and Lua API are in [`GAME_DEFINITION.md`](GAME_DEFINITION.md) and [`API_REFERENCE.md`](API_REFERENCE.md); the boundary is enforced by `EngineBoundaryTest`. The history of each phase is in `docs/DESIGN.md` (roadmap) and `wiki/log.md`.

> **Terminology (ADR 0018, 2026-11-21):** in this document "script" and "Lua" mean what the project now calls a **plugin**: sandboxed Lua in a game's `scripts/` that adds to or replaces the engine's systems through the kernel seams (`register.plugin`, `register.requirement`, `register.monster`, `register.warp` and the rest of `docs/API_REFERENCE.md`). The trusted Kotlin jars described under "plugins" below are **add-on systems**: server-layer extensions that react and refuse (hooks, windows, storage) but cannot change rules. The code names (`MorganaPlugin`, `plugins/`, `morgana-plugin.json`) are unchanged. The seams table in section 6 is largely closed: see `wiki/systems/system_seams.md`.

## 1. Decisions (owner, 2026-11-07)

| # | Question | Decision |
| :-: | :--- | :--- |
| 1 | Purpose | An engine other developers can build games on, in the Mirage Source / Player Worlds style: they change their game with data, scripts and plugins instead of rewriting engine code. |
| 2 | Who writes scripts | Community creators and developers. |
| 3 | Stats | Data-defined. |
| 4 | Combat | Replaceable entirely. |
| 5 | Scripting language | Lua through [Luak](https://github.com/BluevaDevelopment/Luak) (Kotlin Multiplatform Lua 5.5.1, MIT). |

What they imply:
* **The engine and a game are two things.** A game is a directory (game definition, content, scripts, assets); the engine runs whichever game it is pointed at. The engine names no particular game.
* **Sandboxed Lua is the main authoring tier.** Creators share scripts between servers, so scripts are untrusted. Trusted Kotlin plugins are an optional second tier for native speed.
* **The engine does not own combat.** It keeps the world kernel (entities, maps, movement and collision, events, persistence, networking, permissions, replay); combat is one module a game selects.

## 2. Principles (from `AGENTS.md`)
* **Engine invariants are not scriptable:** movement validation and rate limits, authentication, packet limits, bans, persistence integrity, replay. Scripts change *rules*, never the guard rails.
* **Rules are pure queries; changes are recorded effects.** A rule (damage, XP needed) is a deterministic function of its inputs. A change (give item, heal, warp) becomes a `SimInput` and is recorded.
* **Data before code.** JSON and the expression language before Lua; Lua before Kotlin plugins.
* **Validate before apply.** Scripts and game definitions are validated at load and in `check` ([ADR 0007](../wiki/decisions/0007_validate_before_apply.md)).
* **No speculative abstraction.** A port exists where a second implementation is real.

## 3. Architecture as built

```
 Engine kernel    entities, maps, tick, movement + collision, events, persistence, networking, permissions, replay
 Engine services  script host (Luak), registries, game definition loader, content pipeline, editors
 A game           game.json + data/ (JSON content) + scripts/ (Lua) + assets/   [+ optional Kotlin plugins]
```

### 3.1 A game is a directory
```
games/<name>/
  game.json   id, name, engineApi, attributes, vitals, progression, combat formulas and constants, UI profile, clock, capabilities
  data/       maps, items, classes, npcs, spells, ...
  scripts/    Lua modules
  assets/     art and sound the game owns
```
The server starts with `--game <dir>` (or `MORGANA_GAME`); `server-data/` holds world *state* (accounts, characters, saves). `games/blank` is the engine's starting point and `games/roguelike` a worked example; `new-game` scaffolds more. A data directory without a `game.json` runs the neutral `BlankGame`. `engineApi` (`EngineApiVersion.CURRENT`) lets the loader reject a game written for an incompatible engine.

### 3.2 Data-defined stats and vitals
`game.json` declares attributes, vitals (each with a `max` formula and optional regeneration, exactly one `life` vital), progression (an XP table or expression) and the combat formulas and constants, in a small **integer-only expression language** (`Formula`). `CharacterState` holds attribute and vital **maps**; items, spells, classes, NPCs and status effects refer to them by id; the server sends the definition at login so the HUD and editors render whatever the game declares. Details: [`GAME_DEFINITION.md`](GAME_DEFINITION.md) and the [game definition page](../wiki/domain/game_definition.md).

### 3.3 Systems pipeline and replaceable combat
`CombatModule` (`:core-domain`) is the port for projectiles, per-tick vitals and regeneration, attacks, spell casts and generic actions. A game chooses its module in `game.json` (`combat.module`): `realtime` (the default Kotlin implementation), `none` (`NoOpCombatModule`, for peaceful games) or a Lua module (`register.combat`). Client input that is not an attack or cast arrives as a generic action (`c_action(actionId, params)`) routed to `register.action` handlers. The two checks that prove the claim "combat is replaceable": a Lua module produces bit-identical results to the Kotlin one (a differential test), and a peaceful game with custom actions runs on the unmodified engine.

### 3.4 Script host
* **Port:** `ScriptRuntime` in `:core-domain`, implemented by `LuakScriptRuntime` in `:script-luak`. No Luak type leaves the adapter, so the runtime can be replaced.
* **Sandbox:** no `io`, `os`, `package`, `require`, `debug`, `load*`; text-only chunks; bounded pattern functions; `tostring` and errors sanitised; `math.random` replaced by the simulation's seeded random; per-call instruction budgets and a memory ceiling; a circuit breaker disables a module after repeated failures. Only the instruction count, measured on the running thread, is relied on, so a budget failure replays identically.
* **World API:** reads are immutable snapshots; changes are `ScriptEffect`s that become a recorded `SimInput` (`ExecuteScriptEffect`). A game's `capabilities` list (`damage`, `heal`, `inventory`, `teleport`, `status`, `message`, `visual`, `projectile`, `progression`, `vital`) limits which effects its scripts may cause.
* **Registries:** `register.command`, `register.formula`, `register.event_command`, `register.action`, `register.combat`.
* **Tooling:** `:server-app:validateScripts` (in `check`), hot reload through `/reload`, script errors in the operator log.

### 3.5 Determinism and replay
Effects are recorded as inputs, so replay does not re-run scripts for them. Randomness comes only from the seeded `RandomSource` and time is the tick. Lua state is never persisted: all game state lives in `CharacterState`, world data and the plugin key-value store.

### 3.6 Trust
| Tier | Author | Isolation |
| :--- | :--- | :--- |
| Lua scripts (a game's or the community's) | creators | sandboxed always |
| Kotlin plugins (`plugins/` jars, `PluginManager`) | the server operator | none: full JVM access; never loaded from a pack |

## 4. Why Luak, and what was found
Luak is young (created 2026-07, effectively single-maintainer, CalVer with no API promise), so it is adopted behind the port, vendored under `third-party/` with SHA-256 checksums (`:verifyThirdParty`), and depended on only by `:script-luak`. The probe findings that shaped the sandbox, all reproducible and now part of `LuakSandboxTest`:
* Native pattern matching is not budgeted and can freeze the tick (one `string.find` ran over 11 seconds) → bounded wrappers.
* `Budget.interrupt()` from another thread does not reliably stop a loop → rely on the instruction ceiling only.
* `tostring({})` leaks a memory address and error values can be tables → sanitised.
* Lua's own `math.random` state is not part of simulation state → replaced by the seeded random.
* Memory bombs, runaway loops, deep recursion and `pcall` around a budget error are all stopped; seeded randomness and `pairs()` order are identical across lanes and JVM runs; a rule call costs about 1 microsecond once warm.
Not verified: Luak under TeaVM (the web client's compiler) and on Android.

## 5. What was built

| Phase | Work |
| :-: | :--- |
| 0 | Toolchain: Kotlin 2.4, Gradle 8.14, Luak vendored in `:script-luak`. |
| 1 | `game.json`, the expression language, id maps in `CharacterState`, content schema v2, protocol v2, generic HUD and editors. |
| 2 | Script host: `ScriptRuntime`, sandbox, budgets, world API, `ScriptEffect`, registries, `validateScripts`, hot reload. |
| 3 | `CombatModule`, `c_action`, the Kotlin, no-op and Lua combat modules. |
| 4 | `games/` and `--game`; the reference game as a project. The original reference game was later deleted; `games/blank` and `games/roguelike` remain. |
| 5 | Open-engine tooling: `engineApi` versioning, `new-game` scaffolder, script capabilities, [`API_REFERENCE.md`](API_REFERENCE.md) and [`CREATOR_GUIDE.md`](CREATOR_GUIDE.md), an end-to-end test of a creator who never edits engine code. |
| after | Game-owned art, the engine/game boundary test, combat constants in `game.json`, Kotlin plugin jars with a lifecycle and key-value store, plugin messages and server-described windows. |

## 6. Seams still hard-coded, and open questions

Engine vocabulary that games cannot yet extend by data or script (effort: S days, M 1-2 weeks, L weeks and touches persistence or protocol):

| System | Today | Seam | Effort |
| :--- | :--- | :--- | :-: |
| Status effects | 11-value enum | effect definitions as data plus Lua callbacks | M |
| Item types and item use | fixed enum | `onUse` effect list or callback; item types as data | M |
| NPC AI | `NpcBehavior` enum | behavior registry, native defaults | M |
| Quest objectives | 6-value enum | counter objectives bound to named events | M |
| Tile attributes | sealed class | `Custom(typeId, params)` with enter, leave and interact handlers | M-L |
| New content collections with editors | `ContentCollection` enum, hand-built editors | schema-driven editors | L |
| Per-character plugin data | `switches` and `variables` only | namespaced `extensions` on characters | M |

Open questions:
1. **License and governance** for an open engine (permissive or copyleft) and the contribution model.
2. **Repository layout:** keep games under `games/` until the API settles, or split early.
3. **Luak posture:** depend on upstream with mirroring, or fork; who owns upstream contributions.
4. **Creator tooling:** external editor with hot reload, or an in-game script editor as well.
5. **Client-side Lua** (UI scripting) through the same runtime, which needs a TeaVM check first.

Risks to keep in view: Luak's youth (mitigated by the port, pinning and the adversarial suite); Lua cost in per-entity tick loops (keep heavy loops native and measure first); an API that is too thin forces engine changes per game and one that is too wide freezes the engine (drive it with real games and version it); and a shared script is only as safe as the sandbox profile, so the adversarial suite grows with every finding.
