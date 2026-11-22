---
type: Decision
title: "ADR 0013: Engine/Game Split with Data-Defined Stats and Lua Scripting (Luak)"
description: "Direction for opening the engine to other developers: games are directories of data and sandboxed Lua scripts, stats are data-defined, combat is replaceable, and Luak is the Lua runtime."
tags: [adr, plugins, scripting, architecture]
code: ["docs/PLUGIN_ARCHITECTURE.md", "core-domain/src/main/kotlin/com/morgana/domain/game/GameDefinition.kt"]
symbols: ["Luak", "GameDefinition"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T22:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0013: Engine/Game Split with Data-Defined Stats and Lua Scripting (Luak)

**Status**: Accepted as direction on 2026-11-07 (decided by the project owner). Phases 0 to 5 are implemented (toolchain and vendored Luak, data-defined stats, the sandboxed Lua host, replaceable combat, `games/` projects, and the open-engine tooling); the engine/game boundary is enforced by `EngineBoundaryTest`. What remains is listed in `docs/PLUGIN_ARCHITECTURE.md`.

**Context**: the goal is a reference game on the engine and, later, an open engine that other developers adapt through scripts and plugins in the Mirage Source / PlayerWorlds style, instead of rewriting engine code. At the time of the decision there was no plugin loading, hooks were notify-only, and the five stats, three vitals and combat rules were hard-coded; the [extensibility map](/reference/extensibility_map.md) describes today's code.

**Decisions**
1. **Audience**: community creators and developers. Scripts are therefore untrusted by default and sandboxed.
2. **Stats are data-defined**: attributes and vitals are declared in a game definition and referenced by id everywhere, not fields in code.
3. **Combat is replaceable entirely**: the engine keeps the world kernel and runs a pipeline of systems; combat is a module a game selects.
4. **Lua through [Luak](https://github.com/BluevaDevelopment/Luak)** (Kotlin Multiplatform Lua 5.5.1, MIT), behind an engine-owned `ScriptRuntime` port. Trusted Kotlin plugins stay an optional second tier.
5. **A game is a directory** (game definition, data, scripts, assets); the current game becomes the reference game.

**Boundary enforcement (2026-11-14)**: `EngineBoundaryTest` keeps engine modules free of game names and `games/` paths; the built-in Morgana Classic game left `:core-domain` for a test fixture and the engine default is `BlankGame`; combat module ids are validated with no silent fallback; five combat constants became `game.json` fields.

**Evidence for Luak** (probed 2026-11-07): instruction budget, memory ceiling, text-only chunks and cheap isolated lanes behave as documented; runaway loops, memory bombs and deep recursion end as ordinary Lua errors; seeded randomness and `pairs()` order were identical across separate JVM runs; a rule call costs about 1 microsecond. **Problems found** that the host must handle: native string-pattern matching is not budgeted and can hang a thread for seconds (bounded wrappers needed), `Budget.interrupt()` from another thread is unreliable (rely on instruction ceilings, which are also deterministic), and `tostring` of a table leaks an address. **Risks**: the library is about three and a half months old and effectively single-maintainer, it is hosted outside Maven Central, and it requires Kotlin 2.4 (this project was on 2.1.0 when decided).

**Consequences**: a Kotlin and Gradle upgrade comes first; data-defined stats are a large migration (persistence, protocol, content schemas, editors, HUD) that regenerates the golden replay and needs an equivalence proof; the script host must carry the probe cases as its permanent sandbox tests; Luak must stay behind the port with pinned, mirrored artifacts so it can be replaced or forked.
