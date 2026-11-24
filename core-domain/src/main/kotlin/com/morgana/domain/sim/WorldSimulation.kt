package com.morgana.domain.sim

import com.morgana.domain.systems.ArenaMatch
import com.morgana.domain.systems.SchedulerSystem
import com.morgana.domain.game.ScheduleDefinition
import com.morgana.domain.game.ScheduledOneShot
import com.morgana.domain.systems.BossEncounterSystem
import com.morgana.domain.systems.ThreatSystem
import com.morgana.domain.systems.DurabilitySystem
import com.morgana.domain.systems.GearUpgradeSystem
import com.morgana.domain.systems.addTradeOfferItemImpl
import com.morgana.domain.systems.guildBankDepositImpl
import com.morgana.domain.systems.guildBankWithdrawImpl
import com.morgana.domain.systems.initiateTradeImpl
import com.morgana.domain.systems.lockAndConfirmTradeImpl
import com.morgana.domain.systems.setGuildBankAccessImpl
import com.morgana.domain.systems.upgradeGuildBankImpl
import com.morgana.domain.systems.RespecSystem
import com.morgana.domain.systems.ScriptedSystem
import com.morgana.domain.systems.ScriptedWarps
import com.morgana.domain.systems.ScriptedTicks
import com.morgana.domain.systems.Stall
import com.morgana.domain.systems.StallSystem
import com.morgana.domain.systems.ArenaSystem
import com.morgana.domain.systems.BoonSystem
import com.morgana.domain.systems.RunLifecycleSystem
import com.morgana.domain.systems.SkillTreeSystem
import com.morgana.domain.systems.ArenaTicket
import com.morgana.domain.systems.GuildWarState
import com.morgana.domain.systems.GuildWarSystem
import com.morgana.domain.systems.InstancingSystem
import com.morgana.domain.systems.PetSystem
import com.morgana.domain.systems.PromotionSystem
import com.morgana.domain.systems.RebirthSystem
import com.morgana.domain.access.AccessLevel
import com.morgana.domain.access.PermissionFlag
import com.morgana.domain.combat.CombatModule
import com.morgana.domain.combat.RealtimeCombatModule
import com.morgana.domain.content.AnimationDefinition
import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.entity.MapProjectile
import com.morgana.domain.entity.PkMode
import com.morgana.domain.entity.MapResourceState
import com.morgana.domain.entity.PartyState
import com.morgana.domain.entity.TradeSessionState
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.CommonEventTrigger
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.event.EventTriggerType
import com.morgana.domain.event.PlayerQuestState
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.event.QuestStatus
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.ToolType
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.VitalType
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.VitalRole
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.TileCoord
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.StatusEffectType
import com.morgana.domain.stats.VitalState
import com.morgana.domain.script.NoOpScriptRuntime
import com.morgana.domain.script.ScriptEntitySnapshot
import com.morgana.domain.script.ScriptMapSnapshot
import com.morgana.domain.script.ScriptNpcSnapshot
import com.morgana.domain.script.ScriptPlayerSnapshot
import com.morgana.domain.script.ScriptRuntime
import com.morgana.domain.script.ScriptWorldView
import com.morgana.domain.script.ScriptEffect
import kotlin.math.abs
import kotlin.math.max

/**
 * Pure, deterministic 20 TPS (50ms) authoritative ORPG simulation engine.
 * Free of any network, database, filesystem, or LibGDX UI dependencies.
 */
class WorldSimulation(
    val rng: RandomSource = SeededRandomSource(0xFADE2026L),
    val game: CompiledGame,
    val scripts: ScriptRuntime = NoOpScriptRuntime(),
    var combat: CombatModule = CombatModule.forId(game.definition.combat.module),
    /** Total simulation ticks elapsed across previous server runs. */
    var worldEpochTicks: Long = 0L
) {
    /** Volatile so network threads (packet limiting) can read it while the tick thread advances it. */
    @Volatile
    var currentTick: Long = 0L
        internal set

    /** Total elapsed simulation ticks across the lifetime of the world (epoch + current run). */
    fun effectiveWorldTick(): Long = worldEpochTicks + currentTick

    internal var entitySeq: Long = 1L

    val maps: MutableMap<Int, MapDefinition> = linkedMapOf()
    val classes: MutableMap<Int, ClassDefinition> = linkedMapOf()
    val items: MutableMap<Int, ItemDefinition> = linkedMapOf()
    val npcs: MutableMap<Int, NpcDefinition> = linkedMapOf()
    val spells: MutableMap<Int, SpellDefinition> = linkedMapOf()
    val shops: MutableMap<Int, ShopDefinition> = linkedMapOf()
    val resources: MutableMap<Int, ResourceDefinition> = linkedMapOf()
    val recipes: MutableMap<Int, RecipeDefinition> = linkedMapOf()
    val quests: MutableMap<Int, QuestDefinition> = linkedMapOf()
    val events: MutableMap<Int, EventDefinition> = linkedMapOf()
    val commonEvents: MutableMap<Int, CommonEventDefinition> = linkedMapOf()
    val animations: MutableMap<Int, AnimationDefinition> = linkedMapOf()
    /** Action-combat skills and the weapon sets (combo graphs) over them; read by an action combat module (ADR 0017). */
    val actionSkills: MutableMap<Int, com.morgana.domain.content.ActionSkillDefinition> = linkedMapOf()
    val weaponSets: MutableMap<Int, com.morgana.domain.content.WeaponSetDefinition> = linkedMapOf()

    /**
     * Each character's action-combat state (skill in progress, buffered input, cooldowns), kept by `ActionCombatModule`. Sorted by id
     * so it hashes the same however it was filled. Not saved: it is rebuilt from play.
     */
    val actionStates: java.util.TreeMap<String, com.morgana.domain.combat.ActionState> = java.util.TreeMap()

    /**
     * State kept for scripts between hook calls (see [ScriptEffect.SetState]): an action-combat skill in progress, a
     * buffered input. Sorted by key so it hashes the same however it was filled. Not saved: it is rebuilt from play.
     */
    val scriptState: java.util.TreeMap<String, Map<String, String>> = java.util.TreeMap()

    /** Whole-world event switches and numbers shared across all players and persisted across restarts. */
    val worldSwitches: MutableMap<String, Boolean> = linkedMapOf()
    val worldVariables: MutableMap<String, Int> = linkedMapOf()
    val worldStringVariables: MutableMap<String, String> = linkedMapOf()

    val players: MutableMap<String, CharacterState> = linkedMapOf()

    /**
     * The tick at which each character last dealt or took player-versus-player damage. Not saved and not part of the state hash
     * (it is recomputed from the inputs); the server reads it to decide whether a disconnect counts as leaving a fight.
     */
    val lastPvpCombatTick: MutableMap<String, Long> = HashMap()

    /** Whether [characterId] dealt or took PvP damage within the last [withinTicks] ticks. */
    fun inPvpCombat(characterId: String, withinTicks: Long): Boolean {
        val last = lastPvpCombatTick[characterId] ?: return false
        return currentTick - last <= withinTicks
    }
    val mapNpcs: MutableMap<Int, MutableList<MapNpcInstance>> = linkedMapOf()
    val mapGroundItems: MutableMap<Int, MutableList<MapGroundItem>> = linkedMapOf()
    val mapProjectiles: MutableMap<Int, MutableList<MapProjectile>> = linkedMapOf()
    val mapResources: MutableMap<Int, MutableMap<TileCoord, MapResourceState>> = linkedMapOf()
    val unlockedDoors: MutableMap<Int, MutableMap<TileCoord, Long>> = linkedMapOf()

    val parties: MutableMap<String, PartyState> = linkedMapOf()

    /** Open instances of instanced maps, by the copy's map id (see [MapInstances]). Not saved: they close on restart. */
    val instances: MutableMap<Int, MapInstance> = linkedMapOf()

    /** Next id for a new instance; ids are never reused within a run. */
    internal var nextInstanceId: Int = MapInstances.FIRST_ID

    /** Limits for instances; set from `server.json` before ticking. */
    var instanceSettings: InstanceSettings = InstanceSettings()
    val pendingPartyInvites: MutableMap<String, String> = linkedMapOf() // targetCharId -> partyId
    val guilds: MutableMap<String, GuildDocument> = linkedMapOf()

    /** Player stalls by seller character id (see `StallDefinition`); in memory only, a stall closes when its seller leaves. */
    val stalls: MutableMap<String, Stall> = linkedMapOf()

    /** Active dungeon runs keyed by instance owner key (`party:<id>` or `solo:<charId>`). Not saved; hashed when non-empty. */
    val activeRuns: MutableMap<String, com.morgana.domain.game.DungeonRunState> = linkedMapOf()

    /** Optional per-character custom seeds set via `/seed` for the next generated floor. Not saved; hashed when non-empty. */
    val customRunSeeds: MutableMap<String, Long> = linkedMapOf()

    /** Active two-way return portals keyed by instance owner key (`party:<id>` or `solo:<charId>`). Not saved; hashed when non-empty. */
    val returnPortals: MutableMap<String, ReturnPortal> = linkedMapOf()

    /** Active `/boon offer` choices per character id. Not saved; hashed when non-empty. */
    val pendingBoonOffers: MutableMap<String, List<String>> = linkedMapOf()

    /** The running Guild War, if any (see `GuildWarDefinition`). */
    val guildWar: GuildWarState = GuildWarState()

    /** Players waiting for an arena match, in the order they joined (see `ArenaDefinition`). */
    val arenaQueue: MutableMap<String, ArenaTicket> = linkedMapOf()

    /** Arena fights in progress by match id. */
    val arenaMatches: MutableMap<Int, ArenaMatch> = linkedMapOf()
    internal var nextArenaMatchId: Int = 1
    val tradeSessions: MutableMap<String, TradeSessionState> = linkedMapOf()

    /** Where each player last opened a crafting station (transient, never persisted). See [CraftingAnchor]. */
    val craftingAnchors: MutableMap<String, CraftingAnchor> = linkedMapOf()

    /** The shop each player has open (see [ShopSession]); transient like [craftingAnchors], cleared at logout. */
    val shopSessions: MutableMap<String, ShopSession> = linkedMapOf()

    /** What each player has sold to the shop they have open and can buy back (see [ShopBuybackList]); transient, cleared at logout. */
    val shopBuybacks: MutableMap<String, ShopBuybackList> = linkedMapOf()

    /** The dialogue each player's event is waiting on (transient, never persisted). See [PendingDialogue]. */
    val pendingDialogues: MutableMap<String, PendingDialogue> = linkedMapOf()

    /** The wait timer each player's event is paused on (transient, never persisted). See [PendingEventWait]. */
    val pendingEventWaits: MutableMap<String, PendingEventWait> = linkedMapOf()

    /** The text-input prompt each player's event is waiting on (transient, never persisted). See [PendingTextInput]. */
    val pendingTextInputs: MutableMap<String, PendingTextInput> = linkedMapOf()

    /** Last tick each player swung at a resource node, used to rate-limit harvesting/mining. */
    internal val lastHarvestTick: MutableMap<String, Long> = linkedMapOf()

    /** Nesting depth of map-entry (autorun) events, guarding against warp loops. */
    internal var autorunDepth: Int = 0

    /** Nesting depth of automatic common events (login / level-up / timer), guarding against recursive triggers. */
    internal var commonEventDepth: Int = 0


    /** Current entity id counter, exposed read-only for replay state hashing. */
    fun entitySeqSnapshot(): Long = entitySeq

    internal fun nextId(prefix: String): String = "$prefix-${entitySeq++}"


    /** Which optional modules are switched off (default: none). Set before ticking; see [OptionalModule]. */
    var modules: ModuleFlags = ModuleFlags.ALL_ENABLED

    /**
     * The systems installed in this world, in install order (see [install]). The kernel never names a system: it asks the seam
     * lists below, which [install] fills from whatever seams each system implements.
     */
    val systems: MutableList<Any> = mutableListOf()

    /** Systems that carry out event commands that are theirs, asked in order until one takes the command ([EventCommandHandler]). */
    val eventCommandHandlers: MutableList<EventCommandHandler> = mutableListOf()

    /** Systems that may refuse a character the use of a spell or item, asked after the kernel's own checks ([RequirementGate]). */
    val requirementGates: MutableList<RequirementGate> = mutableListOf()

    /** Systems that run every tick, in install order within a [TickStage] ([TickParticipant]). */
    val tickParticipants: MutableList<TickParticipant> = mutableListOf()

    /** Systems with a say in who may fight, what a death means and how hits on monsters land ([CombatArbiter]). */
    val combatArbiters: MutableList<CombatArbiter> = mutableListOf()

    /** Systems that share in what a player earns by a kill ([KillObserver]). */
    val killObservers: MutableList<KillObserver> = mutableListOf()

    /** Systems that can stop a character doing something for now ([ActionGate]). */
    val actionGates: MutableList<ActionGate> = mutableListOf()

    /** Systems that wind down when a character is pulled away from what they were doing ([ActivityInterrupter]). */
    val activityInterrupters: MutableList<ActivityInterrupter> = mutableListOf()

    /** Systems that react to the blows of a fight ([CombatObserver]). */
    val combatObservers: MutableList<CombatObserver> = mutableListOf()

    /** Systems that add to the way monsters think, fight and die ([MonsterBehavior]). */
    val monsterBehaviors: MutableList<MonsterBehavior> = mutableListOf()

    /** Systems that decide where a warp really goes ([WarpRouter]). */
    val warpRouters: MutableList<WarpRouter> = mutableListOf()

    /** Systems that own slash commands ([ChatCommandHandler]). */
    val chatCommandHandlers: MutableList<ChatCommandHandler> = mutableListOf()

    /** Systems that give some items a use of their own ([ItemUseHandler]). */
    val itemUseHandlers: MutableList<ItemUseHandler> = mutableListOf()

    /** Systems that follow real-world time ([WallClockListener]). */
    val wallClockListeners: MutableList<WallClockListener> = mutableListOf()

    /** Systems that react to scheduled warnings and occurrences ([ScheduleHandler]). */
    val scheduleHandlers: MutableList<ScheduleHandler> = mutableListOf()

    /** The wall-clock time (epoch milliseconds, UTC) the server last reported through `SimInput.WallClock`; 0 before the first. */
    var wallClockMillis: Long = 0L
        internal set

    /** The wall-clock time up to which the scheduler has already raised what was due; 0 until the first `WallClock`. Saved with the server state. */
    var scheduleCursorMillis: Long = 0L

    /** Schedule id to the due time of the last occurrence the scheduler started, so a restart never starts one twice. Saved with the server state. */
    val scheduleLastFired: MutableMap<String, Long> = linkedMapOf()

    /** One-off schedules created while the game runs (a siege window a player booked), by id; each is removed when it has started. Saved with the server state. */
    val scheduleOneShots: MutableMap<String, ScheduledOneShot> = linkedMapOf()

    /**
     * Installs [system] in every seam it implements. A game or add-on installs its own before ticking; installing the same
     * object twice does nothing. Systems that keep state keep it in the kernel's state fields, so a replay agrees.
     */
    fun install(system: Any) {
        if (systems.any { it === system }) return
        systems += system
        if (system is EventCommandHandler) eventCommandHandlers += system
        if (system is RequirementGate) requirementGates += system
        if (system is TickParticipant) tickParticipants += system
        if (system is CombatArbiter) combatArbiters += system
        if (system is KillObserver) killObservers += system
        if (system is ActionGate) actionGates += system
        if (system is ActivityInterrupter) activityInterrupters += system
        if (system is CombatObserver) combatObservers += system
        if (system is MonsterBehavior) monsterBehaviors += system
        if (system is WarpRouter) warpRouters += system
        if (system is ChatCommandHandler) chatCommandHandlers += system
        if (system is ItemUseHandler) itemUseHandlers += system
        if (system is WallClockListener) wallClockListeners += system
        if (system is ScheduleHandler) scheduleHandlers += system
    }

    init {
        // The engine's own systems; the order is the order they run in within a seam.
        listOf(
            RebirthSystem, PromotionSystem, ArenaSystem, GuildWarSystem, PetSystem,
            StallSystem, DurabilitySystem, GearUpgradeSystem, RespecSystem,
            ThreatSystem, BossEncounterSystem, RunLifecycleSystem, BoonSystem, SkillTreeSystem, SchedulerSystem,
            ScriptedWarps, // before instancing: a plugin sees a warp as it was made, and its redirect is routed again
            InstancingSystem,
            ScriptedSystem, // last: the engine's own systems give their opinion before a game's Lua plugins
            ScriptedTicks(TickStage.AFTER_NPCS), ScriptedTicks(TickStage.AFTER_EVENTS)
        ).forEach(::install)
    }

    companion object {
        /** Seed used by the live server; recorded in replay logs. */
        const val DEFAULT_SEED: Long = 0xFADE2026L
    }

    // -------------------------------------------------------------------------
    // Map & World Initialization
    // -------------------------------------------------------------------------

    fun registerMap(map: MapDefinition) {
        maps[map.id] = map
        syncMapNpcSpawns(map)
        syncMapResourceNodes(map)
    }


    /** The variant rolled for [npc] at spawn, ignoring any dungeon scaling. */
    internal fun rolledVariant(npc: MapNpcInstance): com.morgana.domain.game.EnemyVariantDefinition? {
        val vid = npc.variantId ?: return null
        return game.definition.combat.enemyVariants.firstOrNull { it.id == vid }
    }

    /**
     * What toughens [npc]: its rolled variant, with the dungeon's [NpcScale] folded in (attack and XP multiply; the level
     * bonus is added for display and XP offsets through [npcLevelShift], not here). Null for an ordinary monster.
     */
    internal fun npcVariant(npc: MapNpcInstance): com.morgana.domain.game.EnemyVariantDefinition? {
        val v = rolledVariant(npc)
        val s = npc.scale ?: return v
        val base = v ?: com.morgana.domain.game.EnemyVariantDefinition(id = "scaled", label = "", levelBonus = 0)
        return base.copy(
            attackPercent = base.attackPercent * s.attackPercent / 100,
            xpPercent = base.xpPercent * s.xpPercent / 100,
            dropRatePercent = base.dropRatePercent * s.dropRatePercent / 100
        )
    }

    /** Levels a dungeon's scaling adds to (or takes from) [npc] for XP offsets. */
    internal fun npcLevelShift(npc: MapNpcInstance): Int = npc.scale?.levelBonus ?: 0

    internal fun rollVariantForNpc(npcDef: NpcDefinition?): com.morgana.domain.game.EnemyVariantDefinition? {
        if (npcDef == null) return null
        val variants = game.definition.combat.enemyVariants
        if (variants.isEmpty()) return null
        if (npcDef.behavior != NpcBehavior.PASSIVE && npcDef.behavior != NpcBehavior.ATTACK_ON_SIGHT) return null
        if (npcDef.xpReward <= 0L || npcDef.title.contains("Boss", ignoreCase = true)) return null
        if (!monsterMayBeElite(npcDef)) return null // a scripted boss is never also an elite
        val roll = rng.nextInt(10_000)
        var acc = 0
        for (v in variants) {
            if (v.spawnBasisPoints <= 0) continue
            acc += v.spawnBasisPoints
            if (roll < acc) return v
        }
        return null
    }

    internal fun spawnVitalsForNpc(
        npcDef: NpcDefinition?,
        variant: com.morgana.domain.game.EnemyVariantDefinition?,
        scale: com.morgana.domain.entity.NpcScale? = null
    ): Map<String, VitalState> = game.definition.vitals.associate { v ->
        val baseMax = npcDef?.vitals?.get(v.id) ?: if (v.role == VitalRole.LIFE) 50 else 20
        val variantMax = if (v.role == VitalRole.LIFE && variant != null) {
            ((baseMax.toLong() * variant.hpPercent) / 100L).toInt().coerceAtLeast(1)
        } else {
            baseMax
        }
        val maxVal = if (v.role == VitalRole.LIFE && scale != null) {
            ((variantMax.toLong() * scale.hpPercent) / 100L).toInt().coerceAtLeast(1)
        } else {
            variantMax
        }
        v.id to VitalState(cur = maxVal, max = maxVal)
    }

    private fun syncMapNpcSpawns(map: MapDefinition) {
        val list = mapNpcs.getOrPut(map.id) { mutableListOf() }
        list.clear()
        for (slot in map.npcSpawns) {
            val npcDef = npcs[slot.npcId]
            val variant = rollVariantForNpc(npcDef)
            val vMap = spawnVitalsForNpc(npcDef, variant)
            list.add(
                MapNpcInstance(
                    instanceId = "npc-${map.id}-${slot.slotIndex}",
                    spawnSlotIndex = slot.slotIndex,
                    npcId = slot.npcId,
                    mapId = map.id,
                    tileX = slot.spawnX.coerceIn(0, map.width - 1),
                    tileY = slot.spawnY.coerceIn(0, map.height - 1),
                    homeX = slot.spawnX.coerceIn(0, map.width - 1),
                    homeY = slot.spawnY.coerceIn(0, map.height - 1),
                    vitals = vMap,
                    variantId = variant?.id
                )
            )
        }
    }


    private fun syncMapResourceNodes(map: MapDefinition) {
        val nodeMap = mapResources.getOrPut(map.id) { linkedMapOf() }
        nodeMap.clear()
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                val cell = map.getCell(x, y) ?: continue
                val attr = cell.attribute
                if (attr is TileAttribute.ResourceNode) {
                    val resDef = resources[attr.resourceId]
                    val maxHealth = resDef?.maxHealth ?: 3
                    val coord = TileCoord(x, y)
                    nodeMap[coord] = MapResourceState(
                        mapId = map.id,
                        tileX = x,
                        tileY = y,
                        resourceId = attr.resourceId,
                        currentHealth = maxHealth,
                        depleted = false,
                        respawnAtTick = 0L
                    )
                }
            }
        }
    }


    fun spawnOrUpdatePlayer(character: CharacterState): List<WorldSimulationEvent> {
        val isNewSpawn = character.id !in players
        // A character entering the simulation was saved by an earlier run whose clock may be ahead of this one.
        val fromStore = if (isNewSpawn) positionAfterLogin(withTimersFromThisClock(character)) else character
        // A game that raised its bag size gives existing characters the room at their next login (never takes any away).
        val roomy = if (isNewSpawn) fromStore.copy(inventory = fromStore.inventory.grownTo(game.definition.inventorySlots)) else fromStore
        // A spell restricted to other classes (a game that added the restriction after the character learned it) is forgotten.
        val classSafe = if (isNewSpawn) {
            roomy.copy(
                knownSpellIds = roomy.knownSpellIds.filter { id ->
                    val sp = spells[id]
                    sp == null || sp.classReqIds.isEmpty() || roomy.classId in sp.classReqIds
                }
            )
        } else roomy
        val rested = if (isNewSpawn) withRestedPoolFromAbsence(classSafe) else classSafe
        val recalculated = rested.recalculateVitals(game, items, refill = false)
        players[recalculated.id] = recalculated
        // A character who logged out as a ghost wakes at home: the revive window belongs to the session they died in.
        val settled = if (isNewSpawn && recalculated.isGhost) {
            releaseGhostImpl(recalculated.id)
            players.getValue(recalculated.id)
        } else recalculated
        val out = mutableListOf<WorldSimulationEvent>(
            WorldSimulationEvent.CharacterUpdated(settled),
            WorldSimulationEvent.PlayerMoved(settled, settled.position.mapId, settled.position.mapId)
        )
        if (isNewSpawn) {
            if (game.definition.rested?.enabled == true && settled.restedXp > 0L) {
                out.add(tellPlayer(settled, "You feel rested: kills pay bonus XP for a while.", "GOOD"))
            }
            fireCommonEvents(settled.id, CommonEventTrigger.ON_LOGIN, out)
            fireMapEntryEvents(settled.id, out)
        }
        return out
    }


    /**
     * [currentTick] restarts at 0 on every launch, but `lastMoveTick`, `lastAttackTick` and `spellCooldowns` are saved
     * with the character as absolute ticks. A value from a longer previous run would sit in this run's future and keep
     * rejecting the player's moves, attacks and spells until this clock caught up. Keeps only values this clock could
     * have produced (so logging out and in within one run cannot shed a real cooldown) and drops the rest.
     */
    private fun withTimersFromThisClock(character: CharacterState): CharacterState {
        val keptCooldowns = character.spellCooldowns.filter { (spellId, readyTick) ->
            // A cooldown set by this run is at most one full cooldown ahead of now (it was cast at or before now).
            val spell = spells[spellId]
            readyTick <= currentTick || (spell != null && readyTick - currentTick <= spell.cooldownTicks)
        }
        val moveTick = if (character.lastMoveTick <= currentTick) character.lastMoveTick else 0L
        val attackTick = if (character.lastAttackTick <= currentTick) character.lastAttackTick else 0L
        return if (moveTick == character.lastMoveTick && attackTick == character.lastAttackTick &&
            keptCooldowns.size == character.spellCooldowns.size
        ) {
            character
        } else {
            character.copy(lastMoveTick = moveTick, lastAttackTick = attackTick, spellCooldowns = keptCooldowns)
        }
    }

    fun removePlayer(characterId: String): List<WorldSimulationEvent> {
        if (characterId !in players) return emptyList()
        val out = mutableListOf<WorldSimulationEvent>()
        finishPendingEventsOnDisconnect(characterId, out)
        stalls.remove(characterId)
        val removed = players.remove(characterId) ?: return out
        lastPvpCombatTick.remove(characterId)
        craftingAnchors.remove(characterId)
        shopSessions.remove(characterId)
        shopBuybacks.remove(characterId)
        pendingDialogues.remove(characterId)
        pendingEventWaits.remove(characterId)
        pendingTextInputs.remove(characterId)
        lastHarvestTick.remove(characterId)
        // Cancel any active trade session involving this player
        val activeTrade = tradeSessions.values.firstOrNull { it.initiatorId == characterId || it.partnerId == characterId }
        if (activeTrade != null) {
            tradeSessions.remove(activeTrade.sessionId)
            out.add(WorldSimulationEvent.TradeUpdated(listOf(activeTrade.initiatorId, activeTrade.partnerId), null))
        }
        return out
    }


    // -------------------------------------------------------------------------
    // 20 TPS Deterministic World Tick
    // -------------------------------------------------------------------------

    fun stepTick(deltaMs: Int = 50): List<WorldSimulationEvent> {
        require(deltaMs > 0) { "deltaMs must be positive" }
        currentTick += 1L
        val out = mutableListOf<WorldSimulationEvent>()

        // 1. Expire temporary unlocked doors
        for ((_, doorMap) in unlockedDoors) {
            val iter = doorMap.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (currentTick >= entry.value) {
                    iter.remove()
                }
            }
        }

        // 2. Respawn depleted resource nodes
        for ((_, resOnMap) in mapResources) {
            for ((coord, state) in resOnMap.entries) {
                if (state.depleted && state.respawnAtTick > 0L && currentTick >= state.respawnAtTick) {
                    val resDef = resources[state.resourceId]
                    val restored = state.copy(
                        currentHealth = resDef?.maxHealth ?: 3,
                        depleted = false,
                        respawnAtTick = 0L
                    )
                    resOnMap[coord] = restored
                    out.add(WorldSimulationEvent.ResourceNodeChanged(restored))
                }
            }
        }

        // 3. Despawn old ground items
        for ((mapId, itemList) in mapGroundItems) {
            val removedAny = itemList.removeAll { it.despawnAtTick in 1..currentTick }
            if (removedAny) {
                out.add(WorldSimulationEvent.GroundItemsChanged(mapId, itemList.toList()))
            }
        }

        // 4. Advance active projectiles
        combat.tickProjectiles(this, out)

        // 5. Tick status effects & natural regeneration on players + HealTrap tiles
        combat.tickPlayers(this, out)

        // 6. Tick NPC AI, status effects, combat, and respawns
        tickNpcs(out)

        // 6b. Systems that run after the monsters (the Guild War starts and ends on schedule)
        tickSystems(TickStage.AFTER_NPCS, out)

        // 7. Resume timed event waits whose delay has elapsed
        tickEventWaits(out)

        // 8. Run timer common events whose tick interval matches currentTick
        tickTimedCommonEvents(out)

        // 8b. Systems that run after events (arena fights are paired and run, pets follow their owners and help in fights)
        tickSystems(TickStage.AFTER_EVENTS, out)

        // 9. Close instances that have stood empty long enough
        tickInstances(out)

        return out
    }

    /**
     * True when [movePlayer] would accept a step from [characterId] in [mode] this tick as far as the rate limit goes
     * (other rules, such as walls or a stun, may still refuse it). False for a player who is not in the world.
     */
    fun moveAllowedNow(characterId: String, mode: MovementMode): Boolean {
        val player = players[characterId] ?: return false
        val effective = if (modules.isEnabled(OptionalModule.SUB_TILE_MOVEMENT)) mode else MovementMode.GRID_LOCKED
        return !MovementRules.isTooSoon(player.lastMoveTick, currentTick, effective)
    }

    fun movePlayer(
        characterId: String,
        direction: Direction,
        mode: MovementMode = MovementMode.GRID_LOCKED,
        deltaPixelsX: Float = 0f,
        deltaPixelsY: Float = 0f
    ): List<WorldSimulationEvent> = movePlayerImpl(
        characterId, direction,
        // Free pixel movement is optional: when it is off every move is a grid step.
        if (modules.isEnabled(OptionalModule.SUB_TILE_MOVEMENT)) mode else MovementMode.GRID_LOCKED,
        deltaPixelsX, deltaPixelsY
    )

    fun playerAttack(characterId: String): List<WorldSimulationEvent> = combat.onPlayerAttack(this, characterId)

    fun playerCastSpell(
        characterId: String,
        spellId: Int,
        targetEntityId: String? = null,
        targetTileX: Int = 0,
        targetTileY: Int = 0
    ): List<WorldSimulationEvent> = combat.onPlayerCastSpell(this, characterId, spellId, targetEntityId, targetTileX, targetTileY)

    fun handleAction(
        characterId: String,
        actionId: String,
        params: Map<String, String> = emptyMap()
    ): List<WorldSimulationEvent> {
        val combatResult = combat.handleAction(this, characterId, actionId, params)
        if (combatResult.handled) return combatResult.events
        val scriptEffects = scripts.registry.executeAction(characterId, actionId, params, asScriptWorldView())
        if (scriptEffects != null) {
            val out = mutableListOf<WorldSimulationEvent>()
            for (effect in scriptEffects) {
                out.addAll(applyScriptEffect(effect))
            }
            return out
        }
        return emptyList()
    }

    fun playerInteractFacing(characterId: String): List<WorldSimulationEvent> = playerInteractFacingImpl(characterId)

    fun pickupGroundItem(characterId: String): List<WorldSimulationEvent> = pickupGroundItemImpl(characterId)

    fun useInventorySlot(characterId: String, slotIndex: Int): List<WorldSimulationEvent> = useInventorySlotImpl(characterId, slotIndex)

    fun unequipSlot(characterId: String, slot: EquipmentSlot): List<WorldSimulationEvent> = unequipSlotImpl(characterId, slot)

    fun dropInventorySlot(characterId: String, slotIndex: Int, amount: Int = 1): List<WorldSimulationEvent> = dropInventorySlotImpl(characterId, slotIndex, amount)

    fun allocateStatPoint(characterId: String, statName: String): List<WorldSimulationEvent> = allocateStatPointImpl(characterId, statName)

    private val lastJumpTickByPlayer = mutableMapOf<String, Long>()

    /**
     * Tile-leap jump: leaps directly to an open tile within [JumpDefinition.maxRangeTiles],
     * crossing intermediate obstacles/chasms while consuming [JumpDefinition.staminaCost].
     * No-ops when `game.definition.jump` is null or `enabled == false`.
     */
    fun jumpTo(characterId: String, targetX: Int, targetY: Int): List<WorldSimulationEvent> {
        val jumpDef = game.definition.jump?.takeIf { it.enabled } ?: return emptyList()
        val player = players[characterId] ?: return emptyList()
        if (!player.isAlive(game.lifeVitalId)) return emptyList()
        if (player.isCasting || player.statusEffects.any { it.preventsActions || it.preventsMovement }) return emptyList()
        val lastJump = lastJumpTickByPlayer[characterId]
        if (lastJump != null && currentTick - lastJump < jumpDef.cooldownTicks) return emptyList()

        val map = maps[player.position.mapId] ?: return emptyList()
        if (map.id in jumpDef.disabledMapIds) return emptyList()
        val targetCoord = TileCoord(targetX, targetY)
        if (!map.isInBounds(targetCoord)) return emptyList()
        val targetCell = map.getCell(targetX, targetY) ?: return emptyList()
        if (targetCell.attribute is TileAttribute.Blocked) return emptyList()

        val dx = targetX - player.position.tileX
        val dy = targetY - player.position.tileY
        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        if (dist !in 1..jumpDef.maxRangeTiles) return emptyList()

        // A cost of 0 makes jumping free: no stamina vital is needed and none is touched.
        val curStamina = player.vitals[jumpDef.staminaVitalId]
        if (jumpDef.staminaCost > 0 && (curStamina == null || curStamina.cur < jumpDef.staminaCost)) {
            return listOf(
                WorldSimulationEvent.FloatingTextEmitted(
                    mapId = map.id,
                    tileX = player.position.tileX,
                    tileY = player.position.tileY,
                    text = "Low Stamina",
                    category = "MISS_DODGE",
                    colorHex = "#EAB308",
                    targetCharacterId = characterId
                )
            )
        }

        val dir = when {
            kotlin.math.abs(dx) >= kotlin.math.abs(dy) -> if (dx > 0) Direction.RIGHT else Direction.LEFT
            else -> if (dy > 0) Direction.DOWN else Direction.UP
        }
        val spentVitals = if (jumpDef.staminaCost > 0 && curStamina != null) {
            player.vitals + (jumpDef.staminaVitalId to curStamina.copy(cur = (curStamina.cur - jumpDef.staminaCost).coerceAtLeast(0)))
        } else player.vitals
        val updated = player.copy(
            vitals = spentVitals,
            position = WorldPosition.fromTile(
                mapId = map.id,
                tileX = targetX,
                tileY = targetY,
                direction = dir,
                tileSize = map.tileSize
            )
        )
        lastJumpTickByPlayer[characterId] = currentTick
        players[characterId] = updated
        return listOf(
            WorldSimulationEvent.CharacterUpdated(updated),
            WorldSimulationEvent.PlayerMoved(updated, map.id, map.id)
        )
    }

    /** Sets the player's PvP targeting mode ([PkMode]) and emits a sync + system notice. */
    fun setPkMode(characterId: String, mode: PkMode): List<WorldSimulationEvent> {
        val cur = players[characterId] ?: return emptyList()
        if (cur.pkMode == mode) return emptyList()
        val updated = cur.copy(pkMode = mode)
        players[characterId] = updated
        return listOf(
            WorldSimulationEvent.CharacterUpdated(updated),
            WorldSimulationEvent.ChatEmitted(
                channel = "SYSTEM",
                mapId = updated.position.mapId,
                senderId = "server",
                senderName = "System",
                message = "PK Mode set to ${mode.label}.",
                targetCharacterId = characterId
            )
        )
    }

    /**
     * Returns true when [attacker] is allowed to damage [defender] under the active PK mode rules.
     * Games without `combat.pk` configured ignore [PkMode] so existing OPEN_PVP behavior is unchanged.
     */
    fun canAttackPlayer(attacker: CharacterState, defender: CharacterState): Boolean {
        if (attacker.id == defender.id) return false
        // In an arena fight the teams decide, whatever the PK mode.
        arbitratedMayFight(attacker, defender)?.let { return it }
        if (game.definition.combat.pk == null) return true
        return when (attacker.pkMode) {
            PkMode.PEACE -> false
            PkMode.TEAM -> attacker.partyId == null || attacker.partyId != defender.partyId
            PkMode.GUILD -> (attacker.partyId == null || attacker.partyId != defender.partyId) &&
                (attacker.guildId == null || attacker.guildId != defender.guildId)
            PkMode.PK -> true
        }
    }

    fun shopBuy(characterId: String, shopId: Int, entryIndex: Int, quantity: Int = 1): List<WorldSimulationEvent> = shopBuyImpl(characterId, shopId, entryIndex, quantity)

    /** Buys back entry [entryIndex] of the player's buy-back list at the price the shop paid for it. */
    fun shopBuyback(characterId: String, shopId: Int, entryIndex: Int): List<WorldSimulationEvent> = shopBuybackImpl(characterId, shopId, entryIndex)

    fun shopSell(
        characterId: String,
        shopId: Int,
        inventorySlotIndex: Int = -1,
        quantity: Int = 1,
        slotIndices: List<Int> = emptyList()
    ): List<WorldSimulationEvent> = shopSellImpl(characterId, shopId, inventorySlotIndex, quantity, slotIndices)

    /** Merges stacks and packs the character's bag by item type and name. */
    fun sortInventory(characterId: String): List<WorldSimulationEvent> = sortInventoryImpl(characterId)

    fun guildBankDeposit(characterId: String, inventorySlotIndex: Int, amount: Int = 1, tab: Int = -1): List<WorldSimulationEvent> = if (systemOn(OptionalModule.GUILD_BANK.configKey)) guildBankDepositImpl(characterId, inventorySlotIndex, amount, tab) else emptyList()

    fun upgradeGuildBank(characterId: String): List<WorldSimulationEvent> = if (systemOn(OptionalModule.GUILD_BANK.configKey)) upgradeGuildBankImpl(characterId) else emptyList()

    fun ejectFromGuildHall(characterId: String): List<WorldSimulationEvent> = ejectFromGuildHallImpl(characterId)

    fun guildBankWithdraw(characterId: String, bankSlotIndex: Int, amount: Int = 1): List<WorldSimulationEvent> = if (systemOn(OptionalModule.GUILD_BANK.configKey)) guildBankWithdrawImpl(characterId, bankSlotIndex, amount) else emptyList()

    fun setGuildBankAccess(characterId: String, rank: String): List<WorldSimulationEvent> = if (systemOn(OptionalModule.GUILD_BANK.configKey)) setGuildBankAccessImpl(characterId, rank) else emptyList()

    fun bankDeposit(characterId: String, inventorySlotIndex: Int, amount: Int = 1): List<WorldSimulationEvent> = bankDepositImpl(characterId, inventorySlotIndex, amount)

    fun bankWithdraw(characterId: String, bankSlotIndex: Int, amount: Int = 1): List<WorldSimulationEvent> = bankWithdrawImpl(characterId, bankSlotIndex, amount)

    fun craftRecipe(characterId: String, recipeId: Int): List<WorldSimulationEvent> =
        if (modules.isEnabled(OptionalModule.CRAFTING)) craftRecipeImpl(characterId, recipeId) else emptyList()

    /** Merges two pieces of gear of the same slot to increase the target item's `+N` enhancement. */
    fun mergeGear(characterId: String, targetSlotIndex: Int, materialSlotIndex: Int): List<WorldSimulationEvent> =
        mergeGearImpl(characterId, targetSlotIndex, materialSlotIndex)

    fun initiateTrade(initiatorId: String, partnerId: String): List<WorldSimulationEvent> =
        if (modules.isEnabled(OptionalModule.TRADING)) initiateTradeImpl(initiatorId, partnerId) else emptyList()

    fun addTradeOfferItem(characterId: String, inventorySlotIndex: Int, amount: Int): List<WorldSimulationEvent> =
        if (modules.isEnabled(OptionalModule.TRADING)) addTradeOfferItemImpl(characterId, inventorySlotIndex, amount) else emptyList()

    fun lockAndConfirmTrade(characterId: String, confirmStage: Boolean): List<WorldSimulationEvent> =
        if (modules.isEnabled(OptionalModule.TRADING)) lockAndConfirmTradeImpl(characterId, confirmStage) else emptyList()

    fun resolveActiveEventPage(player: CharacterState, event: EventDefinition): EventPage? = resolveActiveEventPageImpl(player, event)

    fun executeEventCommands(
        characterId: String,
        eventId: Int,
        commands: List<EventCommand>,
        out: MutableList<WorldSimulationEvent>
    ) = executeEventCommandsImpl(characterId, eventId, commands, out)

    fun testEventCommands(
        editorCharacterId: String,
        eventId: Int,
        commands: List<EventCommand>
    ): List<WorldSimulationEvent> = testEventCommandsImpl(editorCharacterId, eventId, commands)

    fun selectDialogueChoice(characterId: String, eventId: Int, choiceIndex: Int): List<WorldSimulationEvent> = selectDialogueChoiceImpl(characterId, eventId, choiceIndex)

    fun submitTextInput(characterId: String, eventId: Int, value: String = "", cancelled: Boolean = false): List<WorldSimulationEvent> =
        submitTextInputImpl(characterId, eventId, value, cancelled)

    fun executeAdminSlashCommand(adminCharacterId: String, rawCommandLine: String): List<WorldSimulationEvent> = executeAdminSlashCommandImpl(adminCharacterId, rawCommandLine)

    fun applyLiveMapEdit(editorCharacterId: String, updatedMap: MapDefinition): List<WorldSimulationEvent> = applyLiveMapEditImpl(editorCharacterId, updatedMap)

    fun applyLiveItemEdit(editorCharacterId: String, item: ItemDefinition): List<WorldSimulationEvent> = applyLiveItemEditImpl(editorCharacterId, item)

    fun applyLiveNpcEdit(editorCharacterId: String, npc: NpcDefinition): List<WorldSimulationEvent> = applyLiveNpcEditImpl(editorCharacterId, npc)

    fun applyLiveSpellEdit(editorCharacterId: String, spell: SpellDefinition): List<WorldSimulationEvent> = applyLiveSpellEditImpl(editorCharacterId, spell)

    fun applyLiveShopEdit(editorCharacterId: String, shop: ShopDefinition): List<WorldSimulationEvent> = applyLiveShopEditImpl(editorCharacterId, shop)

    fun applyLiveResourceEdit(editorCharacterId: String, resource: ResourceDefinition): List<WorldSimulationEvent> = applyLiveResourceEditImpl(editorCharacterId, resource)

    fun applyLiveActionSkillEdit(editorCharacterId: String, skill: com.morgana.domain.content.ActionSkillDefinition): List<WorldSimulationEvent> = applyLiveActionSkillEditImpl(editorCharacterId, skill)
    fun applyLiveWeaponSetEdit(editorCharacterId: String, weaponSet: com.morgana.domain.content.WeaponSetDefinition): List<WorldSimulationEvent> = applyLiveWeaponSetEditImpl(editorCharacterId, weaponSet)
    fun applyLiveClassEdit(editorCharacterId: String, classDefinition: com.morgana.domain.content.ClassDefinition): List<WorldSimulationEvent> = applyLiveClassEditImpl(editorCharacterId, classDefinition)
    fun applyLiveRecipeEdit(editorCharacterId: String, recipe: RecipeDefinition): List<WorldSimulationEvent> = applyLiveRecipeEditImpl(editorCharacterId, recipe)

    fun applyLiveQuestEdit(editorCharacterId: String, quest: QuestDefinition): List<WorldSimulationEvent> = applyLiveQuestEditImpl(editorCharacterId, quest)

    fun applyLiveEventEdit(editorCharacterId: String, event: EventDefinition): List<WorldSimulationEvent> = applyLiveEventEditImpl(editorCharacterId, event)

    fun applyLiveCommonEventEdit(editorCharacterId: String, commonEvent: CommonEventDefinition): List<WorldSimulationEvent> = applyLiveCommonEventEditImpl(editorCharacterId, commonEvent)

    fun applyLiveAnimationEdit(editorCharacterId: String, animation: AnimationDefinition): List<WorldSimulationEvent> = applyLiveAnimationEditImpl(editorCharacterId, animation)

    fun applyScriptEffect(effect: ScriptEffect): List<WorldSimulationEvent> = applyScriptEffectImpl(effect)

    /**
     * Tells the simulation the real-world time (epoch milliseconds, UTC). The server does this about once a second through the
     * recorded `SimInput.WallClock`; systems that follow wall-clock time (the scheduler) run from it.
     */
    fun advanceWallClock(nowMillis: Long): List<WorldSimulationEvent> {
        if (nowMillis <= 0L) return emptyList()
        wallClockMillis = nowMillis
        val out = mutableListOf<WorldSimulationEvent>()
        notifyWallClock(nowMillis, out)
        return out
    }

    /**
     * Schedules a one-off at [atMillis] (epoch milliseconds), with optional advance [warningsSeconds]. It raises the same triggers as
     * a `game.json` schedule and is removed once it has started; [data] comes back on each trigger. False when the scheduler is off,
     * the time is not in the future, or [id] is a configured schedule's. Booking the same [id] again replaces it.
     */
    fun scheduleOnce(
        id: String, atMillis: Long, name: String = "", warningsSeconds: List<Int> = emptyList(), data: Map<String, String> = emptyMap(),
        warningMessage: String = ScheduleDefinition.DEFAULT_WARNING_MESSAGE, fireMessage: String = ScheduleDefinition.DEFAULT_FIRE_MESSAGE
    ): Boolean = SchedulerSystem.scheduleOnce(this, id, atMillis, name, warningsSeconds, data, warningMessage, fireMessage)

    /** Cancels a one-off made with [scheduleOnce]; false if there was none. */
    fun cancelSchedule(id: String): Boolean = scheduleOneShots.remove(id) != null

    /** The game's calendar, or null when its `game.json` has no `clock` section. */
    val clock: com.morgana.domain.game.WorldClock? get() = game.definition.clock?.let { com.morgana.domain.game.WorldClock(it) }

    /** The calendar at the current tick, or null when the game has no clock. */
    fun clockNow(): com.morgana.domain.game.ClockTime? = clock?.at(effectiveWorldTick())

    fun asScriptWorldView(): ScriptWorldView = object : ScriptWorldView {
        override val currentTick: Long get() = this@WorldSimulation.effectiveWorldTick()

        override val clock: com.morgana.domain.game.ClockTime? get() = clockNow()

        override val wallClockMillis: Long get() = this@WorldSimulation.wallClockMillis

        override fun random(min: Int, max: Int): Int {
            return rng.nextIntInclusive(min, max)
        }

        override fun getPlayer(playerId: String): ScriptPlayerSnapshot? {
            val p = players[playerId] ?: return null
            return ScriptPlayerSnapshot(
                id = p.id,
                name = p.name,
                mapId = p.position.mapId,
                x = p.position.tileX,
                y = p.position.tileY,
                direction = p.position.direction,
                level = p.level,
                attributes = p.attributes,
                effectiveAttributes = p.effectiveAttributes(game, items),
                vitals = p.vitals,
                accessLevel = p.accessLevel.rank,
                classId = p.classId,
                lastAttackTick = p.lastAttackTick,
                weaponItemId = p.equipment.getEquipped(EquipmentSlot.WEAPON)?.itemId,
                ammoItemId = p.equipment.getEquipped(EquipmentSlot.AMMO)?.itemId,
                pixelX = (p.position.pixelX + com.morgana.domain.script.PIXELS_PER_TILE / 2).toInt(),
                pixelY = (p.position.pixelY + com.morgana.domain.script.PIXELS_PER_TILE / 2).toInt()
            )
        }

        override fun getNpc(mapId: Int, instanceId: String): ScriptNpcSnapshot? {
            val npc = mapNpcs[mapId]?.firstOrNull { it.instanceId == instanceId } ?: return null
            val npcDef = npcs[npc.npcId]
            val attrs = npcDef?.attributes?.let { baseMap ->
                game.definition.attributes.associate { it.id to (baseMap[it.id] ?: it.base) }
            } ?: emptyMap()
            return ScriptNpcSnapshot(
                instanceId = npc.instanceId,
                npcId = npc.npcId,
                name = npcDef?.name ?: "NPC #$instanceId",
                mapId = mapId,
                x = npc.tileX,
                y = npc.tileY,
                direction = npc.direction,
                vitals = npc.vitals,
                attributes = attrs,
                isAlive = npc.isAlive(game.lifeVitalId),
                targetId = npc.targetEntityId,
                threat = npc.threat
            )
        }

        override fun getMap(mapId: Int): ScriptMapSnapshot? {
            val m = maps[mapId] ?: return null
            return ScriptMapSnapshot(
                id = m.id,
                name = m.name,
                width = m.width,
                height = m.height,
                moral = m.moral.name
            )
        }

        override fun getItem(itemId: Int): ItemDefinition? = items[itemId]

        override fun getContent(kind: String, id: Int): Any? = when (kind) {
            "action_skill" -> actionSkills[id]?.let { com.morgana.domain.script.ScriptContentTree.of(com.morgana.domain.content.ActionSkillDefinition.serializer(), it) }
            "weapon_set" -> weaponSets[id]?.let { com.morgana.domain.script.ScriptContentTree.of(com.morgana.domain.content.WeaponSetDefinition.serializer(), it) }
            else -> null
        }

        override fun getState(key: String): Map<String, String>? = scriptState[key]

        override fun stateKeys(prefix: String): List<String> =
            scriptState.tailMap(prefix, true).keys.takeWhile { it.startsWith(prefix) }.toList()

        override fun getSpell(spellId: Int): SpellDefinition? = spells[spellId]

        override fun getEntitiesInRange(mapId: Int, x: Int, y: Int, radius: Int): List<ScriptEntitySnapshot> {
            val list = mutableListOf<ScriptEntitySnapshot>()
            players.values.filter { it.position.mapId == mapId && abs(it.position.tileX - x) <= radius && abs(it.position.tileY - y) <= radius }.forEach {
                list.add(ScriptEntitySnapshot(isPlayer = true, id = it.id, mapId = mapId, x = it.position.tileX, y = it.position.tileY, name = it.name))
            }
            mapNpcs[mapId]?.filter { it.isAlive(game.lifeVitalId) && abs(it.tileX - x) <= radius && abs(it.tileY - y) <= radius }?.forEach {
                list.add(ScriptEntitySnapshot(isPlayer = false, id = it.instanceId, mapId = mapId, x = it.tileX, y = it.tileY, name = npcs[it.npcId]?.name ?: "NPC"))
            }
            return list
        }
    }
}

/**
 * Records the tile where a player opened a crafting station. A recipe that needs a station is craftable only
 * while the player is still standing on that tile (moving away or logging out invalidates it).
 */
data class CraftingAnchor(
    val stationType: com.morgana.domain.content.CraftingStationType,
    val mapId: Int,
    val tileX: Int,
    val tileY: Int
)
