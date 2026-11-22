# Game Definition (`game.json`): Data-Defined Stats and Vitals

**Status:** design agreed on 2026-11-08 and **fully implemented** (all six steps of section 9). The simulation, content, protocol and clients run from the definition; the neutral default is `BlankGame`, and the original hard-wired rules survive only as the `MorganaClassic` test fixture that the oracle tests compare against. This is phase 1 of the engine/game split in
[`PLUGIN_ARCHITECTURE.md`](PLUGIN_ARCHITECTURE.md) (direction: [ADR 0013](../wiki/decisions/0013_engine_game_split_and_lua_scripting.md)). The current reference for the file format is [the wiki page](../wiki/domain/game_definition.md); sections 3 to 8 below are the original design, kept for the reasoning, and may lag the code in small details.

## 1. Decisions

| Question | Decision |
| :--- | :--- |
| Formulas in phase 1? | **Yes, a small expression language** in `game.json` (max vitals, regeneration, damage, crit and dodge chance, XP curve). Lua plugs into the same slots in phase 2. |
| Number model | **Integers only (fixed-point).** No floating point anywhere in rules. |
| Where it lives | `<data dir>/game.json`, next to `server.json`. If the file is absent the engine default **`BlankGame`** definition is used (updated 2026-11-14; Morgana Classic is now a test fixture). A game folder carries its own (`games/blank/game.json`). |
| Old data | **No migration.** The project is pre-release: old files are rejected with a clear message, and the starter world, fixtures and golden replay are regenerated. |

Consequences of integers-only (checked against today's numbers, so the classic game does not change):
* **XP curve:** today `round(100 * level^1.65)`. A table in `game.json` holds the 99 values generated once from that formula, so XP needed is identical.
* **Crit damage:** today `round(base * 1.5)` with a floor of 2. For whole numbers `base`, `(3*base + 1) / 2` equals it exactly (even bases are exact; odd bases round the half up, as `roundToInt` does).
* **Everything else already uses integer arithmetic** (truncating division such as `agi/3`).

Defaults assumed, open to change (section 11): load at startup only (no hot reload); level and XP stay engine concepts; gold stays an item.

## 2. Scope

In: attributes, vitals, progression and combat formulas as data; characters, items, spells, classes, NPCs and status effects refer to them by id;
the protocol and client HUD and editors become generic.
Out (later phases): Lua formulas, status effects as data, replaceable combat, a `games/` directory layout, plugin-defined content types.

## 3. The file

```json
{
  "schemaVersion": 1,
  "id": "example",
  "name": "Example Game",
  "attributes": [
    { "id": "str", "name": "Strength",     "short": "STR", "base": 5, "floor": 1, "allocatable": true },
    { "id": "def", "name": "Defense",      "short": "DEF", "base": 5, "floor": 1, "allocatable": true },
    { "id": "int", "name": "Intelligence", "short": "INT", "base": 5, "floor": 1, "allocatable": true },
    { "id": "agi", "name": "Agility",      "short": "AGI", "base": 5, "floor": 1, "allocatable": true },
    { "id": "vit", "name": "Vitality",     "short": "VIT", "base": 5, "floor": 1, "allocatable": true }
  ],
  "vitals": [
    { "id": "hp", "name": "Health",  "short": "HP", "color": "#dc2626", "role": "life",
      "max": "max(10, 80 + level*20 + vit*12 + str*3 + bonus)",
      "regen": { "everyTicks": 40, "amount": "max(1, vit/3)" } },
    { "id": "mp", "name": "Mana",    "short": "MP", "color": "#2563eb",
      "max": "max(0, 30 + level*10 + int*10 + bonus)",
      "regen": { "everyTicks": 40, "amount": "max(1, int/3)" } },
    { "id": "sp", "name": "Stamina", "short": "SP", "color": "#16a34a",
      "max": "max(10, 80 + level*5 + agi*6 + vit*4 + bonus)",
      "regen": { "everyTicks": 40, "amount": "max(2, agi/2)" } }
  ],
  "progression": {
    "maxLevel": 100,
    "xpTable": [ 100, 314, 613, 985, 1423, 1923, ... ]
  },
  "combat": {
    "melee":  "raw = a.str*2 + weapon + a.agi/3; mit = d.def + armor/2 + d.vit/4; base = max(1, raw - mit); crit ? max(2, (3*base + 1)/2) : base",
    "ranged": "raw = a.agi*2 + a.str/2 + weapon + ammo; mit = d.def + armor/2; base = max(1, raw - mit); crit ? max(2, (3*base + 1)/2) : base",
    "spell":  "raw = max(1, basePower + (scales ? a.int*2 + a.vit/4 : 0)); heals ? raw : max(1, raw - (d.int/2 + d.def/2))",
    "critChanceBp":  "clamp(250 + a.agi*35, 100, 5000)",
    "dodgeChanceBp": "clamp(300 + (d.agi - a.agi)*30, 100, 4000)"
  }
}
```
(The XP table above is abbreviated; the real one lists 99 values, entry *n* is the XP needed to go from level *n* to level *n + 1*. The first
values shown were computed independently; the committed table will be generated once from today's Kotlin formula and checked against it in a test.
I verified that no entry is near a rounding tie, and that `(3*base + 1)/2` equals `round(base * 1.5)` for every `base` from 1 to 99,999.)

### Attributes
| Field | Meaning |
| :--- | :--- |
| `id` | Lowercase identifier (`[a-z][a-z0-9_]*`), unique, not a reserved word (section 4). Used everywhere data refers to the attribute. |
| `name`, `short` | Display name and the short label for the HUD. |
| `base` | Default base value for a new character when its class does not set one. |
| `floor` | Minimum **effective** value (today every effective stat is floored at 1 after equipment and status modifiers). |
| `cap` (optional) | Maximum effective value. Default: an engine hard cap of 1,000,000, which keeps all arithmetic in range. |
| `allocatable` | The player may spend stat points on it (`AllocateStatPoint` adds 1 to the base value per request, as today). |

**Effective value** of an attribute = `base + equipment bonuses + status modifiers`, then clamped to `floor` and `cap`.

### Vitals
| Field | Meaning |
| :--- | :--- |
| `id`, `name`, `short`, `color` | As for attributes; `color` is the HUD bar color. Array order is display order. |
| `role` | `"life"` on **exactly one** vital: damage hits it and reaching 0 means death. The engine never mentions "hp" itself. |
| `max` | Formula for the maximum. Variables: `level`, every attribute id (effective), and `bonus` (sum of that vital's max-bonuses from equipment). |
| `regen` | `everyTicks` (interval) and `amount` (formula over `level` and attribute ids). Omit for no regeneration. |

Today's rules that carry over: current value is clamped to `0..max`; a respawn or level-up refill sets every vital to its max; healing and damage never exceed the bounds; spell costs are paid from vitals other than the life vital (checked at load).

### Progression
`maxLevel` (1 to 1000) and **exactly one** of `xpTable` (a list of `maxLevel - 1` positive, non-decreasing numbers; entry *n* counting from 0 is the XP to go from level *n + 1* to *n + 2*) and
`xpExpr` (a formula over `level`, which must give at least 1 at every level). Everything is plain JSON (no string-or-object unions), so the schema stays simple.

### Combat
Each entry is one formula string. A multi-step formula writes `name = expression;` steps before the final expression (section 4), as in `melee` above.
Variables: `a.<id>` and `d.<id>` (effective attributes of attacker and defender), `weapon` (attack damage), `armor` (defender armor), `ammo`
(ranged), `crit` (0 or 1), `basePower`, `scales` (0 or 1, the spell's "uses the scaling term" flag; replaces `scalesWithInt`), `heals` (0 or 1).
Results of damage formulas are clamped by the engine to `1..1,000,000` and chance formulas to `0..10,000` basis points, as today.

### Loot (optional)
`"loot": { "rarities": [ ... ], "affixes": [ ... ] }` turns on Diablo-style random item properties: gear that drops from monsters or lies on
generated floors rolls a rarity (weight, minimum item level, affix count, colour, sell-price multiplier) and that many affixes (a bonus to an
attribute, a vital's maximum, weapon damage or armour, with a value range, weight, item-level band and allowed slots). An item opts out with
`"rollAffixes": false`. Without the section nothing rolls. Full format and rules: `wiki/systems/loot_and_affixes.md`.

## 4. Expression language

A formula is a pure function of its inputs: no state, no loops, no strings, no randomness, no time.

* **Values:** 64-bit integers only. Comparisons and logic return 0 or 1; any non-zero value is "true".
* **Operators** (high to low precedence): unary `-` `!`; `*` `/` `%`; `+` `-`; `<` `<=` `>` `>=`; `==` `!=`; `&&`; `||`; `c ? x : y`.
* **Division and remainder** truncate toward zero (the Kotlin/Java rule, so `agi/3` matches today).
* **Functions:** `min(a, b, ...)`, `max(a, b, ...)`, `clamp(x, lo, hi)`, `abs(x)`, `mulDiv(a, b, c)` (`a*b/c`, truncating, with an exact intermediate product so only the final result must fit), `pow(base, exp)` (integer `exp` from 0 to 16), `isqrt(x)` (integer square root).
* **Overflow, division by zero and out-of-range `pow`** are errors, never wraparound. The validator trial-evaluates every formula at the attribute and level extremes to catch them at load; a runtime error fails that single action deterministically and is logged.
* **Reserved names** (cannot be attribute or vital ids): `level`, `bonus`, `a`, `d`, `weapon`, `armor`, `ammo`, `crit`, `basePower`, `scales`, `heals`, and the function names.
* **Limits:** a formula has at most 1000 characters, 200 parts, 16 steps and 40 levels of nesting. Evaluation cost is therefore bounded and identical on every run, so it can run inside the tick and in replay.
* **Steps:** `name = expression;` before the final expression, at most 16, each usable by what follows; a step name may not be a variable, a function or a reserved name.
* **Names:** `identifier` or `prefix.identifier` (`a.str`). **Phase 2:** a Lua function will be accepted wherever a formula is, with the same inputs (the file syntax for that is decided then).

## 5. How data and state refer to it

| Thing | Today | With a game definition |
| :--- | :--- | :--- |
| Character | `baseStats: Stats`, `vitals: Vitals` (hp/mp/sp and max) | `attributes: {id: base}`, `vitals: {id: {cur, max}}` |
| Class | `baseStats` | `baseAttributes: {id: n}` (missing ids use the attribute's `base`); `statPointsPerLevel` stays |
| Item | `statBonuses`, `statReq`, `bonusMaxHp/Mp/Sp`, `restoreHp/Mp/Sp` | `attributeBonuses`, `attributeReq`, `vitalMaxBonuses`, `restore` (each `{id: n}`) |
| Spell | `mpCost`, `spCost`, `scalesWithInt` | `costs: {id: n}`, `scales: Boolean` |
| NPC | `stats`, `maxHp` | `attributes: {id: n}`, `vitals: {id: max}` (life vital required) |
| Status effect | `statModifier: Stats` | `attributeModifiers: {id: n}` |
| Protocol | `hp/maxHp/mp/...` fields | `vitals` map; the definition itself is sent at login (protocol version bump) |
| Client | fixed HP/MP/SP bars, five stat rows | a bar per declared vital, a row per attribute with `+` for allocatable ones |
| F1 editors | "Bonus STR / INT / AGI", "Restore HP/MP", "MP cost" | rows generated from the definition |
| Hooks | `FormulaOverrides` over `Stats` | removed; the `combat` and `progression` blocks (and Lua in phase 2) replace it |

Content definitions that change shape get `schemaVersion: 2`; version-1 files are rejected with a message naming the file and the new format.

## 6. Validation at startup
Strict parse (unknown fields are errors), then: unique identifier-safe ids shared by attributes and vitals; no reserved names; exactly one `life` vital;
`everyTicks` from 1 to 100000; `#rrggbb` colors; every formula parses and uses only variables available in its slot; limits; attribute
`0 <= floor <= cap <= 1,000,000` and `base` within `0..cap`; exactly one of `xpTable` / `xpExpr`, a table of `maxLevel - 1` entries that are positive and not
decreasing; **trial evaluation** of every formula at the extremes of its inputs (and a few values in between) finds no overflow or division by zero; content files
only reference declared ids (`ContentValidator` gains these cross-reference checks in step 4). Every problem is reported, each with a JSON pointer. A bad `game.json` stops startup with a message, like `server.json` does today.

## 7. No migration: what is regenerated
* The starter world and `StarterWorldSeeder` output, committed test fixtures, and `docs/schemas/*`.
* The golden-master replay baseline. Its state hash covers the old structure, so a new baseline is recorded after the refactor.
* The wiki pages that describe stats, vitals, formulas and the hook API, and the client and editor pages.
Existing local `server-data/` worlds stop loading until recreated (`seedWorld`); the repository's own data is ignored by git.

## 8. Test plan
* **Oracle test (equivalence):** keep a copy of today's `StatFormulas` in test code as a reference. For many generated inputs (all attribute and
  level ranges, both crit values, healing and damage), the Morgana Classic expressions must give exactly the oracle's result, and the XP table must
  match `xpRequiredForNextLevel` for every level.
* **Evaluator tests:** precedence, truncating division, every function, overflow and division-by-zero errors, node and step limits, and determinism
  (the same inputs give the same result across runs and across a recorded replay).
* **Definition tests:** each validation rule, plus a malformed-file test per rule that checks the message.
* **Behavior tests:** the existing combat, regeneration, level-up, respawn, equipment and spell-cost tests, rewritten against ids, must pass with the
  same numbers; a second, deliberately different game definition (different attributes and vitals) runs through login, a fight and a save and load.
* **Wire and client:** protocol round trip of the definition and vitals map; the HUD builds from the definition (tested without GL where possible).

## 9. Implementation order
1. **Model, loader, validation, built-in Morgana Classic, exported schema, `gameDefinition` tool** (done).
2. **Expression language** (parser and evaluator, with the validator doing the static checks) and the oracle tests (done).
3. **Domain refactor:** attribute and vital maps in state; combat, inventory, regeneration, level-up, respawn, equipment and status effects read the definition; remove `Stats`, `Vitals`, `StatFormulas` and `FormulaOverrides`.
4. **Content:** definitions, validator checks, seeder, F1 editors, schemas.
5. **Protocol and clients:** send the definition, vitals map, generic HUD and editors.
6. **Replay baseline and docs:** regenerate the golden replay, update the wiki, remove the dead code. (steps 3 to 6 done)

## 10. Implementation notes (steps 1 and 2)
* Code: `com.morgana.domain.game` in `:core-domain` (`GameDefinition`, `Formula`, `GameDefinitionValidator`, `CompiledGame`, `MorganaClassic`); `GameDefinitionLoader` in
  `:server-persistence`; the `game` command in `ContentTool` (`.\gradlew.bat :server-app:gameDefinition`, with `-PcontentArgs="--write"` to create an editable `game.json`).
* The schema is `docs/schemas/game.schema.json` (drift-tested like the content schemas).
* Tests: `FormulaTest`, `GameDefinitionValidatorTest`, `MorganaClassicOracleTest` (`:core-domain`), `GameDefinitionLoaderTest`
  (`:server-persistence`), `GameCommandTest` (`:server-app`). The oracle test proves Morgana Classic equals the original rules over tens of thousands of generated
  inputs, every XP level, and every small critical-damage case. `OracleMatchesOriginalCodeTest` and `StatFormulas` were removed in step 3.

## 11. Details still open (proposed defaults)
1. `scalesWithInt` becomes a neutral `scales` flag; the formula decides which attributes it uses. *(default: yes)*
2. `FormulaOverrides` is removed in phase 1 rather than adapted, since no plugin uses it and Lua replaces it. *(default: remove)*
3. Spell costs may not name the life vital. *(default: yes, validated)*
4. A runtime formula error fails only that action and is logged, deterministically. *(default: yes)*
5. `level` and XP stay engine concepts; gold stays an item. *(default: yes)*
6. Operators and functions in section 4 are the full set for now; adding one later is a compatible change. *(default: yes)*
7. Whether a second, different test game belongs in the repository permanently as a fixture for the engine. *(default: yes, small)*
