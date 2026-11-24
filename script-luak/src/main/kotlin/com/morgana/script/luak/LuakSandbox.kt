package com.morgana.script.luak

import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.script.ScriptEntitySnapshot
import com.morgana.domain.script.ScriptNpcSnapshot
import com.morgana.domain.script.ScriptPlayerSnapshot
import com.morgana.domain.script.ScriptWorldView
import com.morgana.domain.spatial.Direction
import net.blueva.luak.Globals
import net.blueva.luak.LuaError
import net.blueva.luak.LuaTable
import net.blueva.luak.LuaValue
import net.blueva.luak.Varargs
import net.blueva.luak.lib.LuaPlatform
import net.blueva.luak.lib.OneArgFunction
import net.blueva.luak.lib.TwoArgFunction
import net.blueva.luak.lib.VarArgFunction

/**
 * Sandboxing configuration and security profile for Luak script execution.
 */
object LuakSandbox {
    private val FORBIDDEN_GLOBALS = listOf(
        "io", "os", "package", "require", "dofile", "loadfile",
        "load", "loadstring", "debug", "collectgarbage"
    )

    private val STRING_PATTERN_FUNCS = listOf("find", "match", "gmatch", "gsub")
    private const val MAX_SUBJECT_LENGTH = 4096
    private const val MAX_PATTERN_LENGTH = 256
    private const val MAX_PATTERN_QUANTIFIERS = 3

    private val ADDRESS_REGEX = Regex("(table|userdata|function):\\s*[0-9a-fA-FxX]+")

    internal fun sanitizeAddresses(raw: String): String {
        return ADDRESS_REGEX.replace(raw) { match -> match.groupValues[1] }
    }

    /**
     * Creates a hardened [Globals] environment with stripped dangerous APIs,
     * address-sanitized [tostring], disabled native [math.random], and bounded pattern matching.
     */
    fun createGlobals(): Globals {
        val g = LuaPlatform.standardGlobals()

        // Strip dangerous/untrusted packages and loaders
        for (name in FORBIDDEN_GLOBALS) {
            g.set(name, LuaValue.NIL)
        }

        // Enforce text-only scripts (reject precompiled binary bytecode)
        g.textonly = true

        // Sanitize tostring() against pointer address leaks
        val origToString = g.get("tostring")
        if (origToString != null && origToString.isfunction()) {
            g.set("tostring", object : OneArgFunction() {
                override fun call(arg: LuaValue?): LuaValue? {
                    val raw = origToString.call(arg ?: LuaValue.NIL)?.tojstring() ?: "nil"
                    return LuaValue.valueOf(sanitizeAddresses(raw))
                }
            })
        }

        // Normalize errors so error({}) does not leak table memory address
        val origError = g.get("error")
        if (origError != null && origError.isfunction()) {
            g.set("error", object : TwoArgFunction() {
                override fun call(arg1: LuaValue?, arg2: LuaValue?): LuaValue? {
                    val level = arg2?.optint(1) ?: 1
                    val errVal = if (arg1 != null && (arg1.istable() || arg1.isuserdata())) {
                        LuaValue.valueOf(arg1.typename())
                    } else {
                        arg1 ?: LuaValue.NIL
                    }
                    return origError.call(errVal, LuaValue.valueOf(level))
                }
            })
        }

        // Remove unseeded/unsaved math.random (scripts must use world.random)
        val mathLib = g.get("math")
        if (mathLib != null && mathLib.istable()) {
            mathLib.set("random", LuaValue.NIL)
            mathLib.set("randomseed", LuaValue.NIL)
        }

        // Protect against unbudgeted ReDoS catastrophic backtracking in string pattern matching
        wrapPatternMatching(g)

        return g
    }

    private fun wrapPatternMatching(g: Globals) {
        val strLib = g.get("string")
        if (strLib == null || !strLib.istable()) return

        for (funcName in STRING_PATTERN_FUNCS) {
            val orig = strLib.get(funcName)
            if (orig != null && orig.isfunction()) {
                strLib.set(funcName, object : VarArgFunction() {
                    override fun invoke(args: Varargs): Varargs {
                        val subject = args.arg(1)
                        val pattern = args.arg(2)
                        if (subject.isstring() && pattern.isstring()) {
                            val s = subject.tojstring()
                            val p = pattern.tojstring()
                            val plain = args.optboolean(4, false)
                            if (!plain) {
                                if (s.length > MAX_SUBJECT_LENGTH) {
                                    throw LuaError("string.$funcName subject exceeded max length of $MAX_SUBJECT_LENGTH (was ${s.length})")
                                }
                                if (p.length > MAX_PATTERN_LENGTH) {
                                    throw LuaError("string.$funcName pattern exceeded max length of $MAX_PATTERN_LENGTH (was ${p.length})")
                                }
                                val quantifiers = p.count { it == '*' || it == '+' || it == '-' }
                                if (quantifiers > MAX_PATTERN_QUANTIFIERS) {
                                    throw LuaError("string.$funcName pattern exceeded max quantifiers of $MAX_PATTERN_QUANTIFIERS (had $quantifiers)")
                                }
                            }
                        }
                        return orig.invoke(args)
                    }
                })
            }
        }
    }

    /**
     * Binds the read-only [ScriptWorldView] and effect emission helpers into the `world` global table.
     */
    fun bindWorld(g: Globals, worldView: ScriptWorldView, effectsOut: MutableList<ScriptEffect>): LuaTable {
        val world = LuaValue.tableOf()

        world.set("tick", LuaValue.valueOf(worldView.currentTick))

        // world.now_ms: the real-world time in epoch milliseconds (0 until the server has reported it). A Lua number holds it exactly.
        world.set("now_ms", LuaValue.valueOf(worldView.wallClockMillis.toDouble()))

        // world.schedule_once(id, at_ms, { name=, warnings={seconds,...}, data={k=v}, warning_message=, fire_message= }): book a one-off.
        world.set("schedule_once", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val id = args.checkjstring(1)
                val atMs = args.checkdouble(2).toLong()
                val opts = args.arg(3)
                var name = ""
                var warnings = emptyList<Int>()
                var data = emptyMap<String, String>()
                var warningMessage = com.morgana.domain.game.ScheduleDefinition.DEFAULT_WARNING_MESSAGE
                var fireMessage = com.morgana.domain.game.ScheduleDefinition.DEFAULT_FIRE_MESSAGE
                if (opts != null && opts.istable()) {
                    opts.get("name").let { if (it != null && it.isstring()) name = it.tojstring() }
                    opts.get("warning_message").let { if (it != null && it.isstring()) warningMessage = it.tojstring() }
                    opts.get("fire_message").let { if (it != null && it.isstring()) fireMessage = it.tojstring() }
                    opts.get("warnings").let { w ->
                        if (w != null && w.istable()) warnings = (1..w.length()).mapNotNull { i -> w.get(i).let { v -> if (v != null && v.isnumber()) v.toint() else null } }
                    }
                    opts.get("data").let { d -> if (d != null && d.istable()) data = tableToStringMap(d) }
                }
                effectsOut.add(ScriptEffect.ScheduleOnce(id, atMs, name, warnings, data, warningMessage, fireMessage))
                return LuaValue.NIL
            }
        })


        // world.get_object(id) and world.objects_near(map_id, x, y, radius): what has been built.
        world.set("get_object", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? = worldView.getWorldObject(arg?.checkjstring() ?: return LuaValue.NIL)?.let(::objectToTable) ?: LuaValue.NIL
        })
        world.set("objects_near", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val list = worldView.worldObjectsNear(args.checkint(1), args.checkint(2), args.checkint(3), args.optint(4, 1).coerceIn(0, 64))
                val out = LuaValue.tableOf()
                list.forEachIndexed { i, o -> out.set(i + 1, objectToTable(o)) }
                return out
            }
        })

        // world.place_object(kind, map_id, x, y, [owner_id], [data]), remove_object(id, [reason]), damage_object(id, amount), set_object_data(id, table)
        world.set("place_object", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val owner = args.arg(5)?.takeIf { it.isstring() }?.tojstring()
                val data = args.arg(6)?.takeIf { it.istable() }?.let { tableToStringMap(it) } ?: emptyMap()
                effectsOut.add(ScriptEffect.PlaceObject(args.checkjstring(1), args.checkint(2), args.checkint(3), args.checkint(4), owner, data))
                return LuaValue.NIL
            }
        })
        world.set("remove_object", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                effectsOut.add(ScriptEffect.RemoveObject(args.checkjstring(1), args.optjstring(2, "removed") ?: "removed"))
                return LuaValue.NIL
            }
        })
        world.set("damage_object", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                effectsOut.add(ScriptEffect.DamageObject(args.checkjstring(1), args.checkint(2)))
                return LuaValue.NIL
            }
        })
        world.set("set_object_data", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                effectsOut.add(ScriptEffect.SetObjectData(args.checkjstring(1), args.arg(2)?.takeIf { it.istable() }?.let { tableToStringMap(it) } ?: emptyMap()))
                return LuaValue.NIL
            }
        })

        world.set("cancel_schedule", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                effectsOut.add(ScriptEffect.CancelSchedule(arg?.checkjstring() ?: return LuaValue.NIL))
                return LuaValue.NIL
            }
        })

        // world.clock: the game's calendar (nil when game.json has no clock section).
        worldView.clock?.let { c ->
            val clock = LuaValue.tableOf()
            clock.set("day", LuaValue.valueOf(c.day.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            clock.set("day_of_season", LuaValue.valueOf(c.dayOfSeason))
            clock.set("season", LuaValue.valueOf(c.seasonName))
            clock.set("season_index", LuaValue.valueOf(c.seasonIndex + 1))
            clock.set("year", LuaValue.valueOf(c.year.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            clock.set("time_of_day", LuaValue.valueOf(c.timeOfDayPermille))
            clock.set("phase", LuaValue.valueOf(c.phase.name.lowercase()))
            clock.set("light", LuaValue.valueOf(c.light))
            clock.set("text", LuaValue.valueOf(c.clockText))
            world.set("clock", clock)
        }

        world.set("random", object : TwoArgFunction() {
            override fun call(arg1: LuaValue?, arg2: LuaValue?): LuaValue? {
                val min = arg1?.optint(1) ?: 1
                val max = arg2?.optint(100) ?: 100
                return LuaValue.valueOf(worldView.random(min, max))
            }
        })

        world.set("get_player", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val id = arg?.checkjstring() ?: return LuaValue.NIL
                val p = worldView.getPlayer(id) ?: return LuaValue.NIL
                return playerToTable(p)
            }
        })

        world.set("get_npc", object : TwoArgFunction() {
            override fun call(arg1: LuaValue?, arg2: LuaValue?): LuaValue? {
                val mapId = arg1?.checkint() ?: return LuaValue.NIL
                val instanceId = arg2?.checkjstring() ?: return LuaValue.NIL
                val npc = worldView.getNpc(mapId, instanceId) ?: return LuaValue.NIL
                return npcToTable(npc)
            }
        })

        // world.players_on_map(map_id) and world.npcs_on_map(map_id): every player / monster instance on a map (monsters include dead ones: see is_alive).
        world.set("players_on_map", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val list = worldView.playersOnMap(arg?.checkint() ?: return LuaValue.NIL)
                val arr = LuaValue.tableOf(list.size, 0)
                list.forEachIndexed { i, p -> arr.set(i + 1, playerToTable(p)) }
                return arr
            }
        })
        world.set("npcs_on_map", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val list = worldView.npcsOnMap(arg?.checkint() ?: return LuaValue.NIL)
                val arr = LuaValue.tableOf(list.size, 0)
                list.forEachIndexed { i, n -> arr.set(i + 1, npcToTable(n)) }
                return arr
            }
        })

        // world.population_percent(map_id): how fully the map is supplied by its player count (25 to 100 with `populationScaling`, else 100).
        world.set("population_percent", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? = LuaValue.valueOf(worldView.populationPercent(arg?.checkint() ?: return LuaValue.NIL))
        })

        world.set("get_map", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val mapId = arg?.checkint() ?: return LuaValue.NIL
                val m = worldView.getMap(mapId) ?: return LuaValue.NIL
                val table = LuaValue.tableOf()
                table.set("id", LuaValue.valueOf(m.id))
                table.set("name", LuaValue.valueOf(m.name))
                table.set("width", LuaValue.valueOf(m.width))
                table.set("height", LuaValue.valueOf(m.height))
                table.set("moral", LuaValue.valueOf(m.moral))
                return table
            }
        })

        world.set("get_entities_in_range", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val mapId = args.checkint(1)
                val x = args.checkint(2)
                val y = args.checkint(3)
                val radius = args.optint(4, 5)
                val entities = worldView.getEntitiesInRange(mapId, x, y, radius)
                val arr = LuaValue.tableOf(entities.size, 0)
                entities.forEachIndexed { i, e ->
                    arr.set(i + 1, entityToTable(e))
                }
                return arr
            }
        })

        // Content and script state (action combat): a definition as a table, and state kept between hook calls.
        world.set("get_action_skill", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? = treeToLua(worldView.getContent("action_skill", arg?.checkint() ?: return LuaValue.NIL))
        })

        world.set("get_weapon_set", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? = treeToLua(worldView.getContent("weapon_set", arg?.checkint() ?: return LuaValue.NIL))
        })

        world.set("get_state", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val values = worldView.getState(arg?.checkjstring() ?: return LuaValue.NIL) ?: return LuaValue.NIL
                val t = LuaValue.tableOf()
                for ((k, v) in values) t.set(k, LuaValue.valueOf(v))
                return t
            }
        })

        world.set("state_keys", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val keys = worldView.stateKeys(arg?.checkjstring() ?: return LuaValue.NIL)
                val arr = LuaValue.tableOf(keys.size, 0)
                keys.forEachIndexed { i, k -> arr.set(i + 1, LuaValue.valueOf(k)) }
                return arr
            }
        })

        // world.set_state(key, table): values become strings (whole numbers without a decimal point); nil or {} deletes.
        world.set("set_state", object : TwoArgFunction() {
            override fun call(arg1: LuaValue?, arg2: LuaValue?): LuaValue? {
                val key = arg1?.checkjstring() ?: return LuaValue.NIL
                val values = java.util.TreeMap<String, String>()
                if (arg2 != null && arg2.istable()) {
                    var k: LuaValue = LuaValue.NIL
                    while (true) {
                        val n = arg2.next(k) ?: break
                        k = n.arg1() ?: break
                        if (k.isnil()) break
                        val v = n.arg(2) ?: LuaValue.NIL
                        values[k.tojstring()] = when {
                            v.isboolean() -> if (v.toboolean()) "1" else "0"
                            v.isnumber() -> v.todouble().let { d -> if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
                            else -> v.tojstring()
                        }
                    }
                }
                effectsOut.add(ScriptEffect.SetState(key, values))
                return LuaValue.NIL
            }
        })

        // Effect emission helpers
        world.set("damage_player", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val targetId = args.checkjstring(1)
                val amount = args.checkint(2)
                val vitalId = args.optjstring(3, "hp") ?: "hp"
                val sourceId = args.optjstring(4, null)
                effectsOut.add(ScriptEffect.DamagePlayer(targetId, amount, vitalId, sourceId))
                return LuaValue.NIL
            }
        })

        world.set("damage_npc", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val mapId = args.checkint(1)
                val instanceId = args.checkjstring(2)
                val amount = args.checkint(3)
                val vitalId = args.optjstring(4, "hp") ?: "hp"
                val sourcePlayerId = args.optjstring(5, null)
                effectsOut.add(ScriptEffect.DamageNpc(mapId, instanceId, amount, vitalId, sourcePlayerId))
                return LuaValue.NIL
            }
        })

        world.set("heal_player", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val targetId = args.checkjstring(1)
                val amount = args.checkint(2)
                val vitalId = args.optjstring(3, "hp") ?: "hp"
                effectsOut.add(ScriptEffect.HealPlayer(targetId, amount, vitalId))
                return LuaValue.NIL
            }
        })

        world.set("heal_npc", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val mapId = args.checkint(1)
                val instanceId = args.checkjstring(2)
                val amount = args.checkint(3)
                val vitalId = args.optjstring(4, "hp") ?: "hp"
                effectsOut.add(ScriptEffect.HealNpc(mapId, instanceId, amount, vitalId))
                return LuaValue.NIL
            }
        })

        world.set("give_item", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val playerId = args.checkjstring(1)
                val itemId = args.checkint(2)
                val amount = args.optint(3, 1)
                effectsOut.add(ScriptEffect.GiveItem(playerId, itemId, amount))
                return LuaValue.NIL
            }
        })

        world.set("remove_item", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val playerId = args.checkjstring(1)
                val itemId = args.checkint(2)
                val amount = args.optint(3, 1)
                effectsOut.add(ScriptEffect.RemoveItem(playerId, itemId, amount))
                return LuaValue.NIL
            }
        })

        world.set("warp_player", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val playerId = args.checkjstring(1)
                val mapId = args.checkint(2)
                val x = args.checkint(3)
                val y = args.checkint(4)
                effectsOut.add(ScriptEffect.WarpPlayer(playerId, mapId, x, y))
                return LuaValue.NIL
            }
        })

        // world.despawn_npcs(map_id [, npc_id]): remove monsters (all, or one kind) without loot; world.reset_map(map_id): fresh monsters, nodes, no loot.
        world.set("despawn_npcs", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                effectsOut.add(ScriptEffect.DespawnNpcs(args.checkint(1), args.optint(2, 0)))
                return LuaValue.NIL
            }
        })
        world.set("reset_map", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                effectsOut.add(ScriptEffect.ResetMap(arg?.checkint() ?: return LuaValue.NIL))
                return LuaValue.NIL
            }
        })

        world.set("broadcast", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val msg = args.checkjstring(1)
                val mapId = if (args.isnil(2) || args.isnoneornil(2)) null else args.toint(2)
                val colorHex = args.optjstring(3, "#E2E8F0") ?: "#E2E8F0"
                effectsOut.add(ScriptEffect.BroadcastMessage(msg, mapId, colorHex))
                return LuaValue.NIL
            }
        })

        world.set("send_message", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val playerId = args.checkjstring(1)
                val msg = args.checkjstring(2)
                val colorHex = args.optjstring(3, "#E2E8F0") ?: "#E2E8F0"
                effectsOut.add(ScriptEffect.SendPlayerMessage(playerId, msg, colorHex))
                return LuaValue.NIL
            }
        })

        world.set("floating_text", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val mapId = args.checkint(1)
                val x = args.checkint(2)
                val y = args.checkint(3)
                val text = args.checkjstring(4)
                val category = args.optjstring(5, "script") ?: "script"
                val colorHex = args.optjstring(6, "#FACC15") ?: "#FACC15"
                effectsOut.add(ScriptEffect.EmitFloatingText(mapId, x, y, text, category, colorHex))
                return LuaValue.NIL
            }
        })

        world.set("animation", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val mapId = args.checkint(1)
                val x = args.checkint(2)
                val y = args.checkint(3)
                val animId = args.checkint(4)
                val soundId = args.optjstring(5, "") ?: ""
                effectsOut.add(ScriptEffect.TriggerAnimation(mapId, x, y, animId, soundId))
                return LuaValue.NIL
            }
        })

        world.set("spawn_projectile", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val mapId = args.checkint(1)
                val sourceEntityId = args.checkjstring(2)
                val isFromPlayer = if (args.arg(3).isboolean()) args.arg(3).toboolean() else true
                val currentTileX = args.checkint(4)
                val currentTileY = args.checkint(5)
                val dirStr = args.optjstring(6, "DOWN") ?: "DOWN"
                val dir = Direction.entries.firstOrNull { it.name.equals(dirStr, ignoreCase = true) } ?: Direction.DOWN
                val remainingTiles = args.optint(7, 4)
                val damage = args.optint(8, 5)
                val spriteId = args.optint(9, 1)
                val ticksPerTile = args.optint(10, 1)
                val spellId = args.optint(11, 0)
                effectsOut.add(
                    ScriptEffect.SpawnProjectile(
                        mapId = mapId,
                        sourceEntityId = sourceEntityId,
                        isFromPlayer = isFromPlayer,
                        currentTileX = currentTileX,
                        currentTileY = currentTileY,
                        direction = dir,
                        remainingTiles = remainingTiles,
                        spriteId = spriteId,
                        damage = damage,
                        ticksPerTile = ticksPerTile,
                        spellId = spellId
                    )
                )
                return LuaValue.NIL
            }
        })

        world.set("award_xp", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val playerId = args.checkjstring(1)
                val amount = args.checklong(2)
                effectsOut.add(ScriptEffect.AwardExperience(playerId, amount))
                return LuaValue.NIL
            }
        })

        world.set("consume_vital", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val playerId = args.checkjstring(1)
                val vitalId = args.checkjstring(2)
                val amount = args.checkint(3)
                effectsOut.add(ScriptEffect.ConsumeVital(playerId, vitalId, amount))
                return LuaValue.NIL
            }
        })

        g.set("world", world)
        return world
    }

    /** Converts a content tree (maps, lists, numbers, strings, booleans; see ScriptContentTree) to a Lua value. */
    fun treeToLua(tree: Any?): LuaValue? = when (tree) {
        null -> LuaValue.NIL
        is Map<*, *> -> LuaValue.tableOf().also { t -> for ((k, v) in tree) t.set(k.toString(), treeToLua(v)) }
        is List<*> -> LuaValue.tableOf(tree.size, 0).also { t -> tree.forEachIndexed { i, v -> t.set(i + 1, treeToLua(v)) } }
        is Boolean -> LuaValue.valueOf(tree)
        is Long -> if (tree in Int.MIN_VALUE..Int.MAX_VALUE) LuaValue.valueOf(tree.toInt()) else LuaValue.valueOf(tree.toDouble())
        is Int -> LuaValue.valueOf(tree)
        is Number -> LuaValue.valueOf(tree.toDouble())
        else -> LuaValue.valueOf(tree.toString())
    }

    /** A Lua table of string, number and boolean values as the string map scripts store (numbers and booleans become text). */
    fun tableToStringMap(table: LuaValue): Map<String, String> {
        val values = java.util.TreeMap<String, String>()
        var k: LuaValue = LuaValue.NIL
        while (true) {
            val n = table.next(k) ?: break
            k = n.arg1() ?: break
            if (k.isnil()) break
            val v = n.arg(2) ?: LuaValue.NIL
            values[k.tojstring()] = when {
                v.isboolean() -> if (v.toboolean()) "1" else "0"
                v.isnumber() -> v.todouble().let { d -> if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
                else -> v.tojstring()
            }
        }
        return values
    }

    /** What `register.schedule` hooks receive: `id`, `name`, `phase` ("warning" or "fire"), `seconds_before`, `occurrence_ms`, `recurring`, `data`. */
    fun scheduleToTable(s: com.morgana.domain.script.ScriptScheduleTrigger): LuaTable {
        val t = LuaValue.tableOf()
        t.set("id", LuaValue.valueOf(s.scheduleId))
        t.set("name", LuaValue.valueOf(s.name))
        t.set("phase", LuaValue.valueOf(s.phase))
        t.set("seconds_before", LuaValue.valueOf(s.secondsBefore))
        t.set("occurrence_ms", LuaValue.valueOf(s.occurrenceMillis.toDouble()))
        t.set("recurring", LuaValue.valueOf(s.recurring))
        val data = LuaValue.tableOf()
        for ((k, v) in s.data) data.set(k, LuaValue.valueOf(v))
        t.set("data", data)
        return t
    }

    fun objectToTable(o: com.morgana.domain.script.ScriptObjectSnapshot): LuaTable {
        val t = LuaValue.tableOf()
        t.set("id", LuaValue.valueOf(o.id))
        t.set("kind", LuaValue.valueOf(o.kind))
        t.set("map_id", LuaValue.valueOf(o.mapId))
        t.set("x", LuaValue.valueOf(o.x))
        t.set("y", LuaValue.valueOf(o.y))
        o.ownerId?.let { t.set("owner_id", LuaValue.valueOf(it)) }
        o.guildId?.let { t.set("guild_id", LuaValue.valueOf(it)) }
        t.set("hp", LuaValue.valueOf(o.hp))
        val data = LuaValue.tableOf()
        for ((k, v) in o.data) data.set(k, LuaValue.valueOf(v))
        t.set("data", data)
        return t
    }

    fun playerToTable(p: ScriptPlayerSnapshot): LuaTable {
        val t = LuaValue.tableOf()
        t.set("px", LuaValue.valueOf(p.pixelX))
        t.set("py", LuaValue.valueOf(p.pixelY))
        t.set("id", LuaValue.valueOf(p.id))
        t.set("name", LuaValue.valueOf(p.name))
        t.set("map_id", LuaValue.valueOf(p.mapId))
        t.set("x", LuaValue.valueOf(p.x))
        t.set("y", LuaValue.valueOf(p.y))
        t.set("direction", LuaValue.valueOf(p.direction.name))
        t.set("level", LuaValue.valueOf(p.level))
        t.set("class_id", LuaValue.valueOf(p.classId))
        t.set("last_attack_tick", LuaValue.valueOf(p.lastAttackTick))
        val weapon = p.weaponItemId
        t.set("weapon_item_id", if (weapon != null) LuaValue.valueOf(weapon) else LuaValue.NIL)
        val ammo = p.ammoItemId
        t.set("ammo_item_id", if (ammo != null) LuaValue.valueOf(ammo) else LuaValue.NIL)
        t.set("access_level", LuaValue.valueOf(p.accessLevel))

        val attrs = LuaValue.tableOf()
        for ((k, v) in p.attributes) {
            attrs.set(k, LuaValue.valueOf(v))
        }
        t.set("attributes", attrs)

        val eff = LuaValue.tableOf()
        for ((k, v) in p.effectiveAttributes) {
            eff.set(k, LuaValue.valueOf(v))
        }
        t.set("effective_attributes", eff)

        val vitals = LuaValue.tableOf()
        for ((k, v) in p.vitals) {
            val vt = LuaValue.tableOf()
            vt.set("cur", LuaValue.valueOf(v.cur))
            vt.set("max", LuaValue.valueOf(v.max))
            vitals.set(k, vt)
        }
        t.set("vitals", vitals)
        return t
    }

    fun npcToTable(n: ScriptNpcSnapshot): LuaTable {
        val t = LuaValue.tableOf()
        t.set("px", LuaValue.valueOf(n.pixelX))
        t.set("py", LuaValue.valueOf(n.pixelY))
        t.set("instance_id", LuaValue.valueOf(n.instanceId))
        t.set("npc_id", LuaValue.valueOf(n.npcId))
        t.set("name", LuaValue.valueOf(n.name))
        t.set("map_id", LuaValue.valueOf(n.mapId))
        t.set("x", LuaValue.valueOf(n.x))
        t.set("y", LuaValue.valueOf(n.y))
        t.set("direction", LuaValue.valueOf(n.direction.name))
        t.set("is_alive", LuaValue.valueOf(n.isAlive))
        t.set("target_id", if (n.targetId != null) LuaValue.valueOf(n.targetId!!) else LuaValue.NIL)
        val threat = LuaValue.tableOf()
        for ((k, v) in n.threat) threat.set(k, LuaValue.valueOf(v))
        t.set("threat", threat)

        val attrs = LuaValue.tableOf()
        for ((k, v) in n.attributes) {
            attrs.set(k, LuaValue.valueOf(v))
        }
        t.set("attributes", attrs)

        val vitals = LuaValue.tableOf()
        for ((k, v) in n.vitals) {
            val vt = LuaValue.tableOf()
            vt.set("cur", LuaValue.valueOf(v.cur))
            vt.set("max", LuaValue.valueOf(v.max))
            vitals.set(k, vt)
        }
        t.set("vitals", vitals)
        return t
    }

    fun entityToTable(e: ScriptEntitySnapshot): LuaTable {
        val t = LuaValue.tableOf()
        t.set("px", LuaValue.valueOf(e.pixelX))
        t.set("py", LuaValue.valueOf(e.pixelY))
        t.set("is_player", LuaValue.valueOf(e.isPlayer))
        t.set("id", LuaValue.valueOf(e.id))
        t.set("map_id", LuaValue.valueOf(e.mapId))
        t.set("x", LuaValue.valueOf(e.x))
        t.set("y", LuaValue.valueOf(e.y))
        t.set("name", LuaValue.valueOf(e.name))
        return t
    }
}
