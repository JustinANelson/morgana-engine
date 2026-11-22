package com.morgana.script.luak

import com.morgana.domain.script.ScriptBudget
import com.morgana.domain.script.ScriptCombatHooks
import com.morgana.domain.script.ScriptCommandDefinition
import com.morgana.domain.script.ScriptCommandResult
import com.morgana.domain.script.ScriptCompilationResult
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.sim.ModuleRegistry
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.script.ScriptActionGateHook
import com.morgana.domain.script.ScriptActivity
import com.morgana.domain.script.ScriptCombatObserverHook
import com.morgana.domain.script.ScriptFightHook
import com.morgana.domain.script.ScriptInterruptHook
import com.morgana.domain.script.ScriptItemSubject
import com.morgana.domain.script.ScriptItemUseHook
import com.morgana.domain.script.ScriptItemUseResult
import com.morgana.domain.script.ScriptKillHook
import com.morgana.domain.script.ScriptTickHook
import com.morgana.domain.script.ScriptWarpHook
import com.morgana.domain.script.ScriptWarpRuling
import com.morgana.domain.script.ScriptTickStage
import com.morgana.domain.script.ScriptMonsterHook
import com.morgana.domain.script.ScriptPlayerSnapshot
import com.morgana.domain.script.ScriptPluginManifest
import com.morgana.domain.script.ScriptRequirementHook
import com.morgana.domain.script.ScriptVariantMultipliers
import com.morgana.domain.script.ScriptRegistry
import com.morgana.domain.script.ScriptRuntime
import com.morgana.domain.script.ScriptWorldView
import net.blueva.luak.Budget
import net.blueva.luak.Globals
import net.blueva.luak.LuaError
import net.blueva.luak.LuaFunction
import net.blueva.luak.LuaTable
import net.blueva.luak.LuaValue
import net.blueva.luak.Prototype
import net.blueva.luak.Varargs
import net.blueva.luak.lib.OneArgFunction
import net.blueva.luak.lib.TwoArgFunction
import net.blueva.luak.lib.VarArgFunction
import org.slf4j.LoggerFactory

/**
 * Sandboxed Lua script runtime powered by Luak.
 *
 * Implements [ScriptRuntime] with strict isolation, deterministic instruction-based
 * budgets, memory ceilings, circuit breakers, and zero Luak type leakage.
 */
class LuakScriptRuntime(
    override val budget: ScriptBudget = ScriptBudget(),
    override val registry: ScriptRegistry = ScriptRegistry()
) : ScriptRuntime {

    private val logger = LoggerFactory.getLogger(LuakScriptRuntime::class.java)

    private val compiledPrototypes = mutableMapOf<String, Prototype>()
    private val moduleErrors = mutableMapOf<String, Int>()
    private val disabledModules = mutableSetOf<String>()

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 5
        private val PLUGIN_ID = Regex("[a-z][a-z0-9_-]{1,31}")
    }

    override fun loadModule(moduleId: String, source: String, world: ScriptWorldView): ScriptCompilationResult {
        if (disabledModules.contains(moduleId)) {
            return ScriptCompilationResult.Error(moduleId, "Module is disabled due to repeated errors")
        }

        val g = LuakSandbox.createGlobals()
        val proto = try {
            g.compile(source, moduleId)
        } catch (e: Exception) {
            val msg = e.message ?: "Compilation error"
            logger.warn("Failed to compile script module {}: {}", moduleId, msg)
            return ScriptCompilationResult.Error(moduleId, msg)
        }

        compiledPrototypes[moduleId] = proto

        // Execute module initialization with the registration table
        val effects = mutableListOf<ScriptEffect>()
        LuakSandbox.bindWorld(g, world, effects)
        bindRegistrar(g, moduleId)

        // Set budget for module execution/initialization
        g.budget = Budget().apply { instructions = budget.actionInstructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()

        val fn = g.bind(proto, g)
        return try {
            fn.call()
            logger.info("Loaded script module '{}'", moduleId)
            ScriptCompilationResult.Success(moduleId)
        } catch (e: LuaError) {
            val msg = e.message ?: "Execution error"
            logger.warn("Error initializing script module {}: {}", moduleId, msg)
            ScriptCompilationResult.Error(moduleId, msg)
        }
    }

    private fun bindRegistrar(g: Globals, moduleId: String) {
        val reg = LuaValue.tableOf()

        reg.set("command", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val name = args.checkjstring(1)
                val desc = args.optjstring(2, "") ?: ""
                val minAccess = args.optint(3, 0)
                val handlerFn = args.checkfunction(4)

                registry.registerCommand(
                    ScriptCommandDefinition(
                        name = name,
                        description = desc,
                        minAccessLevel = minAccess,
                        handler = { player, cmdArgs, worldView ->
                            executeCommand(moduleId, handlerFn, player, cmdArgs, worldView)
                        }
                    )
                )
                return LuaValue.NIL
            }
        })

        reg.set("formula", object : TwoArgFunction() {
            override fun call(arg1: LuaValue?, arg2: LuaValue?): LuaValue? {
                val name = arg1?.checkjstring() ?: return LuaValue.NIL
                val formulaFn = arg2?.checkfunction() ?: return LuaValue.NIL

                registry.registerFormula(name) { params, worldView ->
                    executeFormula(moduleId, formulaFn, params, worldView)
                }
                return LuaValue.NIL
            }
        })

        reg.set("event_command", object : TwoArgFunction() {
            override fun call(arg1: LuaValue?, arg2: LuaValue?): LuaValue? {
                val commandId = arg1?.checkjstring() ?: return LuaValue.NIL
                val handlerFn = arg2?.checkfunction() ?: return LuaValue.NIL

                registry.registerEventCommand(commandId) { player, eventId, params, worldView ->
                    executeEventCommand(moduleId, handlerFn, player, eventId, params, worldView)
                }
                return LuaValue.NIL
            }
        })

        reg.set("event_condition", object : TwoArgFunction() {
            override fun call(arg1: LuaValue?, arg2: LuaValue?): LuaValue? {
                val conditionId = arg1?.checkjstring() ?: return LuaValue.NIL
                val predFn = arg2?.checkfunction() ?: return LuaValue.NIL

                registry.registerEventCondition(conditionId) { player, eventId, params, worldView ->
                    executeEventCondition(moduleId, predFn, player, eventId, params, worldView)
                }
                return LuaValue.NIL
            }
        })

        reg.set("action", object : TwoArgFunction() {
            override fun call(arg1: LuaValue?, arg2: LuaValue?): LuaValue? {
                val actionId = arg1?.checkjstring() ?: return LuaValue.NIL
                val handlerFn = arg2?.checkfunction() ?: return LuaValue.NIL

                registry.registerAction(actionId) { characterId, params, worldView ->
                    executeAction(moduleId, handlerFn, characterId, params, worldView)
                }
                return LuaValue.NIL
            }
        })

        reg.set("combat", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val table = arg?.checktable() ?: return LuaValue.NIL
                val onAttack = table.get("on_attack")
                val onAttackFn = if (onAttack != null && onAttack.isfunction()) onAttack as LuaFunction else null

                val onCast = table.get("on_cast")
                val onCastFn = if (onCast != null && onCast.isfunction()) onCast as LuaFunction else null

                val onTickProj = table.get("on_tick_projectiles")
                val onTickProjFn = if (onTickProj != null && onTickProj.isfunction()) onTickProj as LuaFunction else null

                val onTickPlayers = table.get("on_tick_players")
                val onTickPlayersFn = if (onTickPlayers != null && onTickPlayers.isfunction()) onTickPlayers as LuaFunction else null

                val handleAction = table.get("handle_action")
                val handleActionFn = if (handleAction != null && handleAction.isfunction()) handleAction as LuaFunction else null

                registry.registerCombatHooks(
                    ScriptCombatHooks(
                        onPlayerAttack = onAttackFn?.let { fn ->
                            { charId, worldView -> executeCombatAttack(moduleId, fn, charId, worldView) }
                        },
                        onPlayerCastSpell = onCastFn?.let { fn ->
                            { charId, spellId, targetId, tx, ty, worldView ->
                                executeCombatCast(moduleId, fn, charId, spellId, targetId, tx, ty, worldView)
                            }
                        },
                        onTickProjectiles = onTickProjFn?.let { fn ->
                            { worldView -> executeCombatTick(moduleId, fn, worldView) }
                        },
                        onTickPlayers = onTickPlayersFn?.let { fn ->
                            { worldView -> executeCombatTick(moduleId, fn, worldView) }
                        },
                        handleAction = handleActionFn?.let { fn ->
                            { charId, actionId, params, worldView ->
                                executeCombatAction(moduleId, fn, charId, actionId, params, worldView)
                            }
                        }
                    )
                )
                return LuaValue.NIL
            }
        })

        reg.set("plugin", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val t = arg?.checktable() ?: return LuaValue.NIL
                val id = t.get("id")?.checkjstring() ?: throw LuaError("register.plugin: id is required")
                if (!PLUGIN_ID.matches(id)) throw LuaError("register.plugin: id '$id' must be 2-32 characters: lowercase letters, digits, - and _, starting with a letter")
                val replaces = stringList(t.get("replaces"), "register.plugin replaces")
                replaces.firstOrNull { OptionalModule.fromConfigKey(it) == null || it == OptionalModule.LUA_PLUGINS.configKey }?.let {
                    throw LuaError("register.plugin: '$it' is not an engine system a plugin can replace (use a name from server.json modules, such as threat or promotion)")
                }
                val requires = stringList(t.get("requires"), "register.plugin requires")
                // Strict like server.json: a misspelt system name would otherwise count as "on" and the plugin would never be quiet.
                requires.firstOrNull { OptionalModule.fromConfigKey(it) == null && !ModuleRegistry.isCustomId(it) }?.let {
                    throw LuaError("register.plugin: '$it' is not a system (use the names in server.json modules, such as parties or instancing)")
                }
                registry.registerPlugin(
                    ScriptPluginManifest(
                        moduleId = moduleId,
                        id = id,
                        name = t.get("name")?.optjstring("") ?: "",
                        version = t.get("version")?.optjstring("") ?: "",
                        description = t.get("description")?.optjstring("") ?: "",
                        requires = requires.toSet(),
                        replaces = replaces.toSet()
                    )
                )
                return LuaValue.NIL
            }
        })

        reg.set("requirement", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val fn = arg?.checkfunction() ?: return LuaValue.NIL
                registry.registerRequirementHook(
                    ScriptRequirementHook(moduleId) { subject, player, view ->
                        runQuery(moduleId, budget.formulaInstructions, null, view) { world ->
                            val st = LuaValue.tableOf()
                            st.set("kind", LuaValue.valueOf(subject.kind))
                            st.set("id", LuaValue.valueOf(subject.id))
                            st.set("name", LuaValue.valueOf(subject.name))
                            st.set("level_req", LuaValue.valueOf(subject.levelReq))
                            val result = fn.call(st, LuakSandbox.playerToTable(player), world)
                            if (result != null && result.isstring()) result.tojstring() else null
                        }
                    }
                )
                return LuaValue.NIL
            }
        })

        reg.set("may_fight", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val fn = arg?.checkfunction() ?: return LuaValue.NIL
                registry.registerFightHook(
                    ScriptFightHook(moduleId) { attacker, defender, view ->
                        runQuery(moduleId, budget.formulaInstructions, null, view) { world ->
                            val r = fn.call(LuakSandbox.playerToTable(attacker), LuakSandbox.playerToTable(defender), world)
                            if (r != null && r.isboolean()) r.toboolean() else null
                        }
                    }
                )
                return LuaValue.NIL
            }
        })

        reg.set("tick", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val spec = arg ?: return LuaValue.NIL
                val fn: LuaFunction
                var stage = ScriptTickStage.AFTER_EVENTS
                var every = 1
                if (spec.isfunction()) {
                    fn = spec as LuaFunction
                } else {
                    val t = spec.checktable() ?: throw LuaError("register.tick: give a function or a table")
                    val run = t.get("run")
                    if (run == null || !run.isfunction()) throw LuaError("register.tick: give a run function")
                    fn = run as LuaFunction
                    val st = t.get("stage")
                    if (st != null && !st.isnil()) {
                        stage = st.tojstring() ?: ""
                        if (stage !in ScriptTickStage.ALL) throw LuaError("register.tick: stage must be one of ${ScriptTickStage.ALL.sorted().joinToString()}")
                    }
                    val ev = t.get("every")
                    if (ev != null && !ev.isnil()) {
                        if (!ev.isnumber() || ev.toint() < 1 || ev.toint() > 72_000) throw LuaError("register.tick: every must be a number of ticks from 1 to 72000")
                        every = ev.toint()
                    }
                }
                val atStage = stage
                val period = every
                registry.registerTickHook(
                    ScriptTickHook(moduleId, atStage, period) { view ->
                        val effects = mutableListOf<ScriptEffect>()
                        runQuery(moduleId, budget.actionInstructions, emptyList(), view, effects) { w ->
                            fn.call(w)
                            effects.toList()
                        }
                    }
                )
                return LuaValue.NIL
            }
        })

        reg.set("item_use", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val t = arg?.checktable() ?: return LuaValue.NIL
                val use = t.get("use")
                if (use == null || !use.isfunction()) throw LuaError("register.item_use: give a use function")
                val fn = use as LuaFunction
                val itemIds = intList(t.get("items"), "register.item_use items").toSet()
                registry.registerItemUseHook(
                    ScriptItemUseHook(moduleId, itemIds) { item, player, slotIndex, view ->
                        val effects = mutableListOf<ScriptEffect>()
                        runQuery(moduleId, budget.actionInstructions, ScriptItemUseResult(false), view, effects) { w ->
                            val r = fn.invoke(LuaValue.varargsOf(arrayOf(itemTable(item), LuakSandbox.playerToTable(player), LuaValue.valueOf(slotIndex + 1), w)))
                            ScriptItemUseResult(r.arg1().toboolean(), effects.toList())
                        }
                    }
                )
                return LuaValue.NIL
            }
        })

        reg.set("action_gate", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val t = arg?.checktable() ?: return LuaValue.NIL
                val blocks = t.get("blocks")
                if (blocks == null || !blocks.isfunction()) throw LuaError("register.action_gate: give a blocks function")
                val fn = blocks as LuaFunction
                val activities = stringList(t.get("activities"), "register.action_gate activities").toSet()
                activities.firstOrNull { it !in ScriptActivity.ALL }?.let {
                    throw LuaError("register.action_gate: '$it' is not an activity (use ${ScriptActivity.ALL.joinToString()})")
                }
                registry.registerActionGateHook(
                    ScriptActionGateHook(moduleId, activities) { player, activity, view ->
                        runQuery(moduleId, budget.formulaInstructions, false, view) { w ->
                            fn.call(LuakSandbox.playerToTable(player), LuaValue.valueOf(activity), w)?.toboolean() ?: false
                        }
                    }
                )
                return LuaValue.NIL
            }
        })

        reg.set("on_interrupt", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val fn = arg?.checkfunction() ?: return LuaValue.NIL
                registry.registerInterruptHook(
                    ScriptInterruptHook(moduleId) { player, view ->
                        val effects = mutableListOf<ScriptEffect>()
                        runQuery(moduleId, budget.actionInstructions, emptyList(), view, effects) { w ->
                            fn.call(LuakSandbox.playerToTable(player), w)
                            effects.toList()
                        }
                    }
                )
                return LuaValue.NIL
            }
        })

        reg.set("combat_observer", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val t = arg?.checktable() ?: return LuaValue.NIL
                fun fn(name: String): LuaFunction? = t.get(name).let { if (it != null && it.isfunction()) it as LuaFunction else null }
                val afterAttack = fn("after_attack")
                val afterStruck = fn("after_struck")
                if (afterAttack == null && afterStruck == null) throw LuaError("register.combat_observer: give after_attack, after_struck or both")
                registry.registerCombatObserverHook(
                    ScriptCombatObserverHook(
                        moduleId = moduleId,
                        afterAttack = afterAttack?.let { f ->
                            { player, view ->
                                val effects = mutableListOf<ScriptEffect>()
                                runQuery(moduleId, budget.actionInstructions, emptyList(), view, effects) { w ->
                                    f.call(LuakSandbox.playerToTable(player), w)
                                    effects.toList()
                                }
                            }
                        },
                        afterStruck = afterStruck?.let { f ->
                            { player, view ->
                                val effects = mutableListOf<ScriptEffect>()
                                runQuery(moduleId, budget.actionInstructions, emptyList(), view, effects) { w ->
                                    f.call(LuakSandbox.playerToTable(player), w)
                                    effects.toList()
                                }
                            }
                        }
                    )
                )
                return LuaValue.NIL
            }
        })

        reg.set("warp", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val fn = arg?.checkfunction() ?: return LuaValue.NIL
                registry.registerWarpHook(
                    ScriptWarpHook(moduleId) { player, warp, view ->
                        runQuery(moduleId, budget.formulaInstructions, null, view) { world ->
                            val request = LuaValue.tableOf()
                            request.set("map_id", LuaValue.valueOf(warp.mapId))
                            request.set("name", LuaValue.valueOf(warp.mapName))
                            request.set("x", LuaValue.valueOf(warp.x))
                            request.set("y", LuaValue.valueOf(warp.y))
                            request.set("instanced", LuaValue.valueOf(warp.instanced))
                            val result = fn.invoke(LuaValue.varargsOf(arrayOf(LuakSandbox.playerToTable(player), request, world)))
                            val first = result.arg1()
                            when {
                                first != null && first.isboolean() && !first.toboolean() ->
                                    ScriptWarpRuling.Refuse(result.arg(2)?.takeIf { it.isstring() }?.tojstring())
                                first != null && first.isnumber() -> ScriptWarpRuling.Redirect(first.toint())
                                else -> null
                            }
                        }
                    }
                )
                return LuaValue.NIL
            }
        })

        reg.set("on_kill", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val fn = arg?.checkfunction() ?: return LuaValue.NIL
                registry.registerKillHook(
                    ScriptKillHook(moduleId) { killer, xp, view ->
                        val effects = mutableListOf<ScriptEffect>()
                        runQuery(moduleId, budget.actionInstructions, emptyList(), view, effects) { w ->
                            fn.call(LuakSandbox.playerToTable(killer), LuaValue.valueOf(xp), w)
                            effects.toList()
                        }
                    }
                )
                return LuaValue.NIL
            }
        })

        reg.set("monster", object : OneArgFunction() {
            override fun call(arg: LuaValue?): LuaValue? {
                val t = arg?.checktable() ?: return LuaValue.NIL
                val npcIds = intList(t.get("npcs"), "register.monster npcs").toSet()
                fun fn(name: String): LuaFunction? = t.get(name).let { if (it != null && it.isfunction()) it as LuaFunction else null }
                val holdsStill = fn("holds_still")
                val variant = fn("variant")
                val retarget = fn("retarget")
                val step = fn("step")
                val mayBeElite = fn("may_be_elite")
                val killLockout = fn("kill_lockout")
                if (listOf(holdsStill, variant, retarget, step, mayBeElite, killLockout).all { it == null }) {
                    throw LuaError("register.monster: give at least one of holds_still, variant, retarget, step, may_be_elite, kill_lockout")
                }
                registry.registerMonsterHook(
                    ScriptMonsterHook(
                        moduleId = moduleId,
                        npcIds = npcIds,
                        holdsStill = holdsStill?.let { f ->
                            { npc, view -> runQuery(moduleId, budget.formulaInstructions, false, view) { w -> f.call(LuakSandbox.npcToTable(npc), w)?.toboolean() ?: false } }
                        },
                        variant = variant?.let { f ->
                            { npc, view ->
                                runQuery(moduleId, budget.formulaInstructions, null, view) { w ->
                                    val r = f.call(LuakSandbox.npcToTable(npc), w)
                                    if (r != null && r.istable()) {
                                        ScriptVariantMultipliers(
                                            attackPercent = percentField(r, "attack_percent"),
                                            moveSpeedPercent = percentField(r, "move_speed_percent")
                                        )
                                    } else null
                                }
                            }
                        },
                        retarget = retarget?.let { f ->
                            { npc, view ->
                                runQuery(moduleId, budget.formulaInstructions, null, view) { w ->
                                    val r = f.call(LuakSandbox.npcToTable(npc), w)
                                    if (r != null && r.isstring()) r.tojstring() else null
                                }
                            }
                        },
                        step = step?.let { f ->
                            { npc, view ->
                                val effects = mutableListOf<ScriptEffect>()
                                runQuery(moduleId, budget.actionInstructions, emptyList(), view, effects) { w ->
                                    f.call(LuakSandbox.npcToTable(npc), w)
                                    effects.toList()
                                }
                            }
                        },
                        mayBeElite = mayBeElite?.let { f ->
                            { npcId, view -> runQuery(moduleId, budget.formulaInstructions, true, view) { w -> f.call(LuaValue.valueOf(npcId), w)?.toboolean() ?: true } }
                        },
                        killLockoutTicks = killLockout?.let { f ->
                            { npcId, view ->
                                runQuery(moduleId, budget.formulaInstructions, null, view) { w ->
                                    val r = f.call(LuaValue.valueOf(npcId), w)
                                    if (r != null && r.isnumber()) r.tolong() else null
                                }
                            }
                        }
                    )
                )
                return LuaValue.NIL
            }
        })

        g.set("register", reg)
    }

    /** The table a use hook receives for the item being used. */
    private fun itemTable(item: ScriptItemSubject): LuaTable {
        val t = LuaValue.tableOf()
        t.set("id", LuaValue.valueOf(item.id))
        t.set("name", LuaValue.valueOf(item.name))
        t.set("type", LuaValue.valueOf(item.type))
        t.set("level_req", LuaValue.valueOf(item.levelReq))
        return t
    }

    /** A Lua list of strings (nil is the empty list); anything else in it is a script error that fails the module load. */
    private fun stringList(value: LuaValue?, what: String): List<String> {
        if (value == null || value.isnil()) return emptyList()
        if (!value.istable()) throw LuaError("$what must be a list of strings")
        return (1..value.length()).map { i ->
            val v = value.get(i)
            if (v == null || !v.isstring()) throw LuaError("$what must be a list of strings")
            v.tojstring() ?: ""
        }
    }

    /** A Lua list of whole numbers (nil is the empty list). */
    private fun intList(value: LuaValue?, what: String): List<Int> {
        if (value == null || value.isnil()) return emptyList()
        if (!value.istable()) throw LuaError("$what must be a list of numbers")
        return (1..value.length()).map { i ->
            val v = value.get(i)
            if (v == null || !v.isnumber()) throw LuaError("$what must be a list of numbers")
            v.toint()
        }
    }

    /** A percentage field of a returned table, 100 when it is missing, kept between 0 and 1000. */
    private fun percentField(table: LuaValue, name: String): Int {
        val v = table.get(name)
        return if (v != null && v.isnumber()) v.toint().coerceIn(0, 1000) else 100
    }

    /**
     * Runs one hook call in fresh sandbox globals under an instruction budget and returns what [call] gives, or [default] when
     * the module is switched off or the call fails (which also counts toward the module's circuit breaker). Effects the call
     * causes go to [effects] (a query passes none, so anything it tries to cause is dropped).
     */
    private fun <T> runQuery(
        moduleId: String,
        instructions: Long,
        default: T,
        worldView: ScriptWorldView,
        effects: MutableList<ScriptEffect> = mutableListOf(),
        call: (world: LuaTable) -> T
    ): T {
        if (disabledModules.contains(moduleId)) return default
        val g = LuakSandbox.createGlobals()
        val worldTable = LuakSandbox.bindWorld(g, worldView, effects)
        g.budget = Budget().apply { this.instructions = instructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()
        return try {
            val result = call(worldTable)
            recordSuccess(moduleId)
            result
        } catch (e: Exception) {
            recordFailure(moduleId, e)
            default
        }
    }

    private fun executeCommand(
        moduleId: String,
        fn: LuaFunction,
        player: ScriptPlayerSnapshot,
        args: List<String>,
        worldView: ScriptWorldView
    ): ScriptCommandResult {
        if (disabledModules.contains(moduleId)) {
            return ScriptCommandResult(handled = false, feedbackMessage = "Module '$moduleId' is disabled")
        }

        val g = LuakSandbox.createGlobals()
        val effects = mutableListOf<ScriptEffect>()
        val worldTable = LuakSandbox.bindWorld(g, worldView, effects)
        val playerTable = LuakSandbox.playerToTable(player)

        val argsTable = LuaValue.tableOf(args.size, 0)
        args.forEachIndexed { i, arg -> argsTable.set(i + 1, LuaValue.valueOf(arg)) }

        g.budget = Budget().apply { instructions = budget.commandInstructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()

        return try {
            val result = fn.call(playerTable, argsTable, worldTable)
            recordSuccess(moduleId)
            val msg = if (result != null && result.isstring()) result.tojstring() else null
            ScriptCommandResult(handled = true, feedbackMessage = msg, effects = effects)
        } catch (e: Exception) {
            recordFailure(moduleId, e)
            ScriptCommandResult(handled = false, feedbackMessage = "Script error in $moduleId: ${e.message}")
        }
    }

    private fun executeFormula(
        moduleId: String,
        fn: LuaFunction,
        params: Map<String, Long>,
        worldView: ScriptWorldView
    ): Long {
        if (disabledModules.contains(moduleId)) {
            return 0L
        }

        val g = LuakSandbox.createGlobals()
        val effects = mutableListOf<ScriptEffect>()
        val worldTable = LuakSandbox.bindWorld(g, worldView, effects)

        val paramsTable = LuaValue.tableOf()
        for ((k, v) in params) {
            paramsTable.set(k, LuaValue.valueOf(v))
        }

        g.budget = Budget().apply { instructions = budget.formulaInstructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()

        return try {
            val result = fn.call(paramsTable, worldTable)
            recordSuccess(moduleId)
            result?.tolong() ?: 0L
        } catch (e: Exception) {
            recordFailure(moduleId, e)
            0L
        }
    }

    private fun executeEventCommand(
        moduleId: String,
        fn: LuaFunction,
        player: ScriptPlayerSnapshot,
        eventId: Int,
        params: Map<String, String>,
        worldView: ScriptWorldView
    ): List<ScriptEffect> {
        if (disabledModules.contains(moduleId)) {
            return emptyList()
        }

        val g = LuakSandbox.createGlobals()
        val effects = mutableListOf<ScriptEffect>()
        val worldTable = LuakSandbox.bindWorld(g, worldView, effects)
        val playerTable = LuakSandbox.playerToTable(player)

        val paramsTable = LuaValue.tableOf()
        for ((k, v) in params) {
            paramsTable.set(k, LuaValue.valueOf(v))
        }

        g.budget = Budget().apply { instructions = budget.eventInstructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()

        return try {
            fn.invoke(LuaValue.varargsOf(arrayOf(playerTable, LuaValue.valueOf(eventId), paramsTable, worldTable)))
            recordSuccess(moduleId)
            effects
        } catch (e: Exception) {
            recordFailure(moduleId, e)
            emptyList()
        }
    }

    private fun executeEventCondition(
        moduleId: String,
        fn: LuaFunction,
        player: ScriptPlayerSnapshot,
        eventId: Int,
        params: Map<String, String>,
        worldView: ScriptWorldView
    ): Boolean {
        if (disabledModules.contains(moduleId)) {
            return false
        }

        val g = LuakSandbox.createGlobals()
        val effects = mutableListOf<ScriptEffect>()
        val worldTable = LuakSandbox.bindWorld(g, worldView, effects)
        val playerTable = LuakSandbox.playerToTable(player)

        val paramsTable = LuaValue.tableOf()
        for ((k, v) in params) {
            paramsTable.set(k, LuaValue.valueOf(v))
        }

        g.budget = Budget().apply { instructions = budget.eventInstructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()

        return try {
            val res = fn.invoke(LuaValue.varargsOf(arrayOf(playerTable, LuaValue.valueOf(eventId), paramsTable, worldTable)))
            recordSuccess(moduleId)
            res.arg1().toboolean()
        } catch (e: Exception) {
            recordFailure(moduleId, e)
            false
        }
    }

    private fun executeAction(
        moduleId: String,
        fn: LuaFunction,
        characterId: String,
        params: Map<String, String>,
        worldView: ScriptWorldView
    ): List<ScriptEffect> {
        if (disabledModules.contains(moduleId)) {
            return emptyList()
        }

        val g = LuakSandbox.createGlobals()
        val effects = mutableListOf<ScriptEffect>()
        val worldTable = LuakSandbox.bindWorld(g, worldView, effects)

        val paramsTable = LuaValue.tableOf()
        for ((k, v) in params) {
            paramsTable.set(k, LuaValue.valueOf(v))
        }

        g.budget = Budget().apply { instructions = budget.actionInstructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()

        return try {
            fn.call(LuaValue.valueOf(characterId), paramsTable, worldTable)
            recordSuccess(moduleId)
            effects
        } catch (e: Exception) {
            recordFailure(moduleId, e)
            emptyList()
        }
    }

    private fun executeCombatAttack(
        moduleId: String,
        fn: LuaFunction,
        characterId: String,
        worldView: ScriptWorldView
    ): List<ScriptEffect> {
        if (disabledModules.contains(moduleId)) {
            return emptyList()
        }

        val g = LuakSandbox.createGlobals()
        val effects = mutableListOf<ScriptEffect>()
        val worldTable = LuakSandbox.bindWorld(g, worldView, effects)

        g.budget = Budget().apply { instructions = budget.actionInstructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()

        return try {
            fn.call(LuaValue.valueOf(characterId), worldTable)
            recordSuccess(moduleId)
            effects
        } catch (e: Exception) {
            recordFailure(moduleId, e)
            emptyList()
        }
    }

    private fun executeCombatCast(
        moduleId: String,
        fn: LuaFunction,
        characterId: String,
        spellId: Int,
        targetEntityId: String?,
        targetTileX: Int,
        targetTileY: Int,
        worldView: ScriptWorldView
    ): List<ScriptEffect> {
        if (disabledModules.contains(moduleId)) {
            return emptyList()
        }

        val g = LuakSandbox.createGlobals()
        val effects = mutableListOf<ScriptEffect>()
        val worldTable = LuakSandbox.bindWorld(g, worldView, effects)

        g.budget = Budget().apply { instructions = budget.actionInstructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()

        return try {
            val targetVal = if (targetEntityId != null) LuaValue.valueOf(targetEntityId) else LuaValue.NIL
            fn.invoke(
                LuaValue.varargsOf(
                    arrayOf(
                        LuaValue.valueOf(characterId),
                        LuaValue.valueOf(spellId),
                        targetVal,
                        LuaValue.valueOf(targetTileX),
                        LuaValue.valueOf(targetTileY),
                        worldTable
                    )
                )
            )
            recordSuccess(moduleId)
            effects
        } catch (e: Exception) {
            recordFailure(moduleId, e)
            emptyList()
        }
    }

    private fun executeCombatTick(
        moduleId: String,
        fn: LuaFunction,
        worldView: ScriptWorldView
    ): List<ScriptEffect> {
        if (disabledModules.contains(moduleId)) {
            return emptyList()
        }

        val g = LuakSandbox.createGlobals()
        val effects = mutableListOf<ScriptEffect>()
        val worldTable = LuakSandbox.bindWorld(g, worldView, effects)

        g.budget = Budget().apply { instructions = budget.actionInstructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()

        return try {
            fn.call(worldTable)
            recordSuccess(moduleId)
            effects
        } catch (e: Exception) {
            recordFailure(moduleId, e)
            emptyList()
        }
    }

    private fun executeCombatAction(
        moduleId: String,
        fn: LuaFunction,
        characterId: String,
        actionId: String,
        params: Map<String, String>,
        worldView: ScriptWorldView
    ): List<ScriptEffect> {
        if (disabledModules.contains(moduleId)) {
            return emptyList()
        }

        val g = LuakSandbox.createGlobals()
        val effects = mutableListOf<ScriptEffect>()
        val worldTable = LuakSandbox.bindWorld(g, worldView, effects)

        val paramsTable = LuaValue.tableOf()
        for ((k, v) in params) {
            paramsTable.set(k, LuaValue.valueOf(v))
        }

        g.budget = Budget().apply { instructions = budget.actionInstructions }
        g.memoryceiling = budget.memoryCeilingBytes
        g.startmemorycount()

        return try {
            fn.invoke(
                LuaValue.varargsOf(
                    arrayOf(
                        LuaValue.valueOf(characterId),
                        LuaValue.valueOf(actionId),
                        paramsTable,
                        worldTable
                    )
                )
            )
            recordSuccess(moduleId)
            effects
        } catch (e: Exception) {
            recordFailure(moduleId, e)
            emptyList()
        }
    }

    private fun recordSuccess(moduleId: String) {
        moduleErrors[moduleId] = 0
    }

    private fun recordFailure(moduleId: String, err: Exception) {
        val count = (moduleErrors[moduleId] ?: 0) + 1
        moduleErrors[moduleId] = count
        logger.warn("Script failure in '{}' (consecutive: {}): {}", moduleId, count, err.message)

        if (count >= MAX_CONSECUTIVE_FAILURES) {
            disabledModules.add(moduleId)
            logger.error("Disabled script module '{}' after {} consecutive errors", moduleId, count)
        }
    }

    override fun reset() {
        compiledPrototypes.clear()
        moduleErrors.clear()
        disabledModules.clear()
        registry.clear()
    }

    override fun hasModule(moduleId: String): Boolean = compiledPrototypes.containsKey(moduleId)
    override fun loadedModules(): List<String> = compiledPrototypes.keys.toList()
    override fun isModuleDisabled(moduleId: String): Boolean = disabledModules.contains(moduleId)
}
