package com.morgana.client.ui.mastery

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.net.ClientTransport
import com.morgana.client.state.ClientWorldState
import com.morgana.client.ui.GameTheme
import com.morgana.client.ui.HudWindow
import com.morgana.domain.game.TrainingFocusKind
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.ServerPacket
import kotlin.math.max

/**
 * Dedicated RPG-themed window for Weapon Mastery, Skill Mastery, Single-Tier Food Offline Training,
 * and Daily-Capped Online AFK Training.
 */
class MasteryAndTrainingWindow(
    private val stage: Stage,
    private val worldState: ClientWorldState,
    private val netClient: ClientTransport,
    @Suppress("UNUSED_PARAMETER") private val assets: ProceduralRetroAssets
) : HudWindow("Mastery & Training") {

    private val content = VisTable()
    private var lastSignature: String = ""

    init {
        isMovable = true
        addCloseButton()
        defaults().pad(3f)

        val winW = (stage.width - 24f).coerceIn(320f, 620f)
        val winH = (stage.height - 48f).coerceIn(280f, 480f)

        val scroll = VisScrollPane(content).apply {
            setFadeScrollBars(false)
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
        add(scroll).grow().pad(2f).row()

        setSize(winW, winH)
        centerWindow()
        rebuild()
    }

    override fun act(delta: Float) {
        super.act(delta)
        val sig = stateSignature()
        if (sig != lastSignature) {
            lastSignature = sig
            rebuild()
        }
    }

    private fun stateSignature(): String {
        val ch = worldState.localCharacter ?: return "none"
        val ts = ch.trainingState
        val foods = ch.inventory.slots.mapIndexedNotNull { idx, stack ->
            val item = stack?.let { worldState.catalog.items.firstOrNull { d -> d.id == it.itemId } }
            if (stack != null && item != null && item.trainingTier > 0) "$idx:${stack.itemId}x${stack.amount}" else null
        }.joinToString(",")
        return "${ch.weaponProficiencyLevels}|${ch.weaponProficiencyHits}|${ch.skillMasteryLevels}|${ch.skillMasteryProgress}|$ts|$foods"
    }

    private fun rebuild() {
        content.clearChildren()
        content.top().left().defaults().left().growX().padBottom(6f)
        val ch = worldState.localCharacter ?: return
        val def = worldState.gameDefinition
        val wpCfg = def?.weaponProficiency?.takeIf { it.enabled }
        val smCfg = def?.skillMastery?.takeIf { it.enabled }
        val trCfg = def?.training?.takeIf { it.enabled }
        val ts = ch.trainingState

        // --- 1. Offline & Online AFK Training Card ---
        if (trCfg != null) {
            val trainingCard = VisTable().apply {
                background = GameTheme.shared.cardBackground
                pad(8f)
                defaults().left().padBottom(4f)
            }
            val focusLabel = when (ts.focusKind) {
                TrainingFocusKind.WEAPON -> {
                    val name = wpCfg?.weaponTypes?.firstOrNull { it.id == ts.focusId }?.name
                        ?: ts.focusId.replaceFirstChar { it.uppercase() }
                    "Weapon: $name"
                }
                TrainingFocusKind.SKILL -> {
                    val sid = ts.focusId.toIntOrNull() ?: 0
                    val name = worldState.catalog.actionSkills.firstOrNull { it.id == sid }?.name ?: "Skill #$sid"
                    "Skill: $name"
                }
                else -> "None selected (pick below)"
            }
            trainingCard.add(VisLabel("Active Training Focus: $focusLabel").apply { color = Color(0.96f, 0.84f, 0.48f, 1f) }).growX().row()

            // Offline rations bar
            val rationText = if (ts.foodTier > 0 && ts.fueledSeconds > 0) {
                "Offline Rations (Tier ${ts.foodTier} locked): ${formatDuration(ts.fueledSeconds)} / ${formatDuration(ts.capSeconds)}"
            } else {
                "Offline Rations: Empty (consumed offline only; load 1 food tier)"
            }
            val rationRow = VisTable()
            rationRow.add(VisLabel(rationText)).growX().left()
            if (ts.foodTier > 0 || ts.fueledSeconds > 0) {
                val clearBtn = VisTextButton("Clear", GameTheme.shared.dangerButtonStyle).apply {
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: Actor?) {
                            netClient.send(ClientPacket.ClearOfflineTrainingFuel)
                        }
                    })
                }
                rationRow.add(clearBtn).padLeft(6f)
            }
            trainingCard.add(rationRow).growX().row()

            // Available training foods from bag
            val foodSlots = ch.inventory.slots.mapIndexedNotNull { idx, stack ->
                val item = stack?.let { worldState.catalog.items.firstOrNull { d -> d.id == it.itemId } }
                if (stack != null && item != null && item.trainingTier > 0 && item.trainingMinutes > 0) Triple(idx, stack.amount, item) else null
            }
            if (foodSlots.isEmpty()) {
                trainingCard.add(VisLabel("No training food in bag. Gather/buy Apples or cook Stew & Meals.", GameTheme.shared.mutedLabelStyle)).growX().row()
            } else {
                val foodBox = VisTable().apply {
                    background = GameTheme.shared.insetWellBackground
                    pad(6f)
                    defaults().left().padBottom(3f)
                }
                for ((slotIdx, qty, item) in foodSlots) {
                    val lockedOut = ts.foodTier != 0 && ts.foodTier != item.trainingTier
                    val row = VisTable()
                    val desc = "${item.name} x$qty  (T${item.trainingTier} • +${item.trainingMinutes}m • cap ${item.trainingCapMinutes / 60}h)"
                    row.add(VisLabel(desc).apply {
                        if (lockedOut) color = Color(0.6f, 0.56f, 0.52f, 1f)
                    }).growX().left()
                    val add1 = VisTextButton("+1").apply {
                        isDisabled = lockedOut
                        addListener(object : ChangeListener() {
                            override fun changed(event: ChangeEvent?, actor: Actor?) {
                                netClient.send(ClientPacket.FuelOfflineTraining(slotIdx, 1))
                            }
                        })
                    }
                    val add5 = VisTextButton("+5").apply {
                        isDisabled = lockedOut || qty < 2
                        addListener(object : ChangeListener() {
                            override fun changed(event: ChangeEvent?, actor: Actor?) {
                                netClient.send(ClientPacket.FuelOfflineTraining(slotIdx, 5))
                            }
                        })
                    }
                    row.add(add1).padLeft(6f)
                    row.add(add5).padLeft(4f)
                    foodBox.add(row).growX().row()
                }
                trainingCard.add(foodBox).growX().row()
            }

            // Online AFK Training toggle (no food consumed)
            val dailyCapSec = trCfg.onlineAfkDailyLimitMinutes * 60
            val remSec = (dailyCapSec - ts.afkUsedSecondsToday).coerceAtLeast(0)
            val afkRow = VisTable()
            val afkStatus = if (ts.afkActive) {
                "Online AFK Mode: ACTIVE (${formatDuration(remSec)} / ${trCfg.onlineAfkDailyLimitMinutes}m left today — no food used)"
            } else {
                "Online AFK Mode: Off (${formatDuration(remSec)} / ${trCfg.onlineAfkDailyLimitMinutes}m daily budget — no food used)"
            }
            afkRow.add(VisLabel(afkStatus).apply {
                color = if (ts.afkActive) Color(0.45f, 0.92f, 0.55f, 1f) else Color.WHITE
            }).growX().left()
            val afkBtn = VisTextButton(
                if (ts.afkActive) "Stop AFK" else "Start AFK",
                if (ts.afkActive) GameTheme.shared.dangerButtonStyle else GameTheme.shared.primaryButtonStyle
            ).apply {
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) {
                        netClient.send(ClientPacket.ToggleAfkTraining(!ts.afkActive))
                    }
                })
            }
            afkRow.add(afkBtn).padLeft(6f)
            trainingCard.add(afkRow).growX().padTop(2f).row()

            content.add(trainingCard).growX().row()
        }

        // --- 2. Weapon Mastery Section ---
        if (wpCfg != null) {
            val wpCard = VisTable().apply {
                background = GameTheme.shared.cardBackground
                pad(8f)
                defaults().left().padBottom(4f)
            }
            wpCard.add(VisLabel("Weapon Mastery (+${wpCfg.damageBonusPercentPerLevel}% dmg / level)").apply {
                color = Color(0.95f, 0.82f, 0.45f, 1f)
            }).growX().row()

            val types = if (wpCfg.weaponTypes.isNotEmpty()) {
                wpCfg.weaponTypes.map { it.id to it.name }
            } else {
                ch.weaponProficiencyLevels.keys.sorted().map { it to it.replaceFirstChar { c -> c.uppercase() } }
            }
            for ((typeId, typeName) in types) {
                val lv = ch.weaponProficiencyLevel(typeId)
                val hits = ch.weaponProficiencyHitsFor(typeId)
                val need = max(1, wpCfg.hitsPerLevel) * lv
                val bonus = (lv - 1) * wpCfg.damageBonusPercentPerLevel
                val focused = ts.focusKind == TrainingFocusKind.WEAPON && ts.focusId == typeId
                val row = VisTable()
                val prog = if (lv >= wpCfg.maxLevel) "MAX" else "$hits / $need"
                row.add(VisLabel("$typeName  Lv.$lv  (+$bonus% dmg)  [$prog]").apply {
                    if (focused) color = Color(0.45f, 0.92f, 0.55f, 1f)
                }).growX().left()
                if (trCfg != null) {
                    val btn = VisTextButton(if (focused) "Training" else "Train").apply {
                        isDisabled = focused
                        addListener(object : ChangeListener() {
                            override fun changed(event: ChangeEvent?, actor: Actor?) {
                                netClient.send(ClientPacket.SetTrainingFocus(TrainingFocusKind.WEAPON, typeId))
                            }
                        })
                    }
                    row.add(btn).padLeft(6f)
                }
                wpCard.add(row).growX().row()
            }
            content.add(wpCard).growX().row()
        }

        // --- 3. Skill Mastery Section ---
        if (smCfg != null) {
            val smCard = VisTable().apply {
                background = GameTheme.shared.cardBackground
                pad(8f)
                defaults().left().padBottom(4f)
            }
            smCard.add(VisLabel("Skill Mastery (+${smCfg.damagePercentPerLevel}% dmg / level)").apply {
                color = Color(0.95f, 0.82f, 0.45f, 1f)
            }).growX().row()

            val skills = worldState.catalog.actionSkills
                .filter { it.hitboxes.any { hb -> hb.hit.damagePercent > 0 } }
                .sortedBy { it.id }
            if (skills.isEmpty()) {
                smCard.add(VisLabel("No combat skills found.", GameTheme.shared.mutedLabelStyle)).row()
            } else {
                for (sk in skills) {
                    val lv = ch.skillMasteryLevel(sk.id)
                    val progVal = ch.skillMasteryProgressFor(sk.id)
                    val need = max(1, smCfg.usesPerLevel) * lv
                    val bonus = (lv - 1) * smCfg.damagePercentPerLevel
                    val focused = ts.focusKind == TrainingFocusKind.SKILL && ts.focusId == sk.id.toString()
                    val row = VisTable()
                    val prog = if (lv >= smCfg.maxLevel) "MAX" else "$progVal / $need"
                    row.add(VisLabel("${sk.name}  Lv.$lv  (+$bonus% dmg)  [$prog]").apply {
                        if (focused) color = Color(0.45f, 0.92f, 0.55f, 1f)
                    }).growX().left()
                    if (trCfg != null) {
                        val btn = VisTextButton(if (focused) "Training" else "Train").apply {
                            isDisabled = focused
                            addListener(object : ChangeListener() {
                                override fun changed(event: ChangeEvent?, actor: Actor?) {
                                    netClient.send(ClientPacket.SetTrainingFocus(TrainingFocusKind.SKILL, sk.id.toString()))
                                }
                            })
                        }
                        row.add(btn).padLeft(6f)
                    }
                    smCard.add(row).growX().row()
                }
            }
            content.add(smCard).growX().row()
        }
    }

    private fun formatDuration(totalSeconds: Int): String {
        val m = (totalSeconds / 60).coerceAtLeast(0)
        val h = m / 60
        val remM = m % 60
        return if (h > 0) "${h}h ${remM}m" else "${remM}m"
    }
}

/**
 * Compact login summary modal shown when offline food-fueled training completed while the player was away.
 */
class OfflineTrainingReportWindow(
    stage: Stage,
    report: ServerPacket.OfflineTrainingReport
) : HudWindow("Offline Training Complete") {
    init {
        isMovable = true
        addCloseButton()
        pad(10f)
        defaults().pad(3f)
        defaults().left().padBottom(5f)

        val hrs = report.minutesTrained / 60
        val mins = report.minutesTrained % 60
        val dur = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
        add(VisLabel("Trained ${report.focusName} for $dur while offline.").apply {
            color = Color(0.96f, 0.84f, 0.48f, 1f)
        }).row()
        add(VisLabel("Progress earned: +${report.hitsAwarded} mastery hits")).row()
        val levelLine = if (report.endLevel > report.startLevel) {
            "Level increased: Lv.${report.startLevel} -> Lv.${report.endLevel}!"
        } else {
            "Current level: Lv.${report.endLevel}"
        }
        add(VisLabel(levelLine).apply {
            if (report.endLevel > report.startLevel) color = Color(0.45f, 0.92f, 0.55f, 1f)
        }).row()
        val remMin = report.remainingFuelSeconds / 60
        add(VisLabel("Remaining offline rations: ${remMin}m", GameTheme.shared.mutedLabelStyle)).row()

        val ok = VisTextButton("Continue", GameTheme.shared.primaryButtonStyle).apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    remove()
                }
            })
        }
        add(ok).center().padTop(6f).row()
        pack()
        val w = width.coerceAtLeast(300f)
        setSize(w, height)
        setPosition((stage.width - w) * 0.5f, (stage.height - height) * 0.5f)
    }
}
