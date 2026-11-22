package com.morgana.client.ui.bag

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.widget.VisTable
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.net.ClientTransport
import com.morgana.client.state.ClientWorldState
import com.morgana.client.state.HotbarSlotAction
import com.morgana.client.ui.GameTheme
import com.morgana.client.ui.HudWindow
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.BlankGame
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRollText
import com.morgana.protocol.ClientPacket

/** The character sheet sections of the [CharacterMenu] (its Inventory tab is the [InventoryPanel]). */
enum class CharacterTab(val label: String) { STATS("Stats"), EQUIPMENT("Equipment"), SKILLS("Skills"), PROFICIENCY("Proficiency") }

/**
 * The character sheet sections of the [CharacterMenu]: stats (with the +1 buttons for unspent points), equipment (one
 * slot per row so Unequip always fits a phone screen), skills or spells, and weapon proficiency. The menu draws the tab
 * bar and calls [showTab]; sections the game does not use are left out of [availableTabs].
 *
 * Like the inventory it is live: the server's updates redraw it, so unequipping or spending a point never closes it.
 */
class CharacterPanel(
    private val uiStage: Stage,
    private val worldState: ClientWorldState,
    private val netClient: ClientTransport,
    private val assets: ProceduralRetroAssets,
    private val winW: Float,
    private val winH: Float,
    startTab: CharacterTab = CharacterTab.STATS,
    private val onAssignHotbar: (HotbarSlotAction) -> Unit
) : VisTable() {

    var tab: CharacterTab = startTab
        private set
    private var selectedSpell: Int? = null
    private var lastSignature: List<Any?>? = null

    private val theme = GameTheme.shared
    private val body = VisTable()
    private val content = VisTable()

    init {
        defaults().pad(3f)
        if (tab !in availableTabs()) tab = CharacterTab.STATS
        add(body).grow().row()
        body.add(BagUi.scroll(content, uiStage)).grow().width(winW - 24f).height(winH - 92f)
        refresh()
    }

    fun showTab(t: CharacterTab) {
        if (t == tab || t !in availableTabs()) return
        tab = t
        refresh()
    }

    fun availableTabs(): List<CharacterTab> {
        val game = (worldState.compiledGame ?: BlankGame.COMPILED).definition
        val wp = game.weaponProficiency
        return buildList {
            add(CharacterTab.STATS)
            add(CharacterTab.EQUIPMENT)
            if (worldState.showSpellsUi()) add(CharacterTab.SKILLS)
            if (wp != null && wp.enabled && wp.weaponTypes.isNotEmpty()) add(CharacterTab.PROFICIENCY)
        }
    }

    fun tabLabel(t: CharacterTab): String =
        if (t == CharacterTab.SKILLS) worldState.abilityTerminology().pluralLabel else t.label

    override fun act(delta: Float) {
        super.act(delta)
        val ch = worldState.localCharacter ?: return
        val signature = listOf(
            ch.inventory, ch.equipment, ch.level, ch.xp, ch.unspentStatPoints, ch.attributes,
            ch.vitals, ch.knownSpellIds, ch.spellLevels, ch.weaponProficiencyLevels, ch.weaponProficiencyHits,
            ch.variables, worldState.catalog.items.size
        )
        if (signature != lastSignature) {
            lastSignature = signature
            refresh()
        }
    }

    private fun items(): Map<Int, ItemDefinition> = worldState.catalog.items.associateBy { it.id }

    private fun names(): StatNames {
        val game = (worldState.compiledGame ?: BlankGame.COMPILED).definition
        return StatNames(
            attributes = game.attributes.associate { it.id to it.short.ifBlank { it.name } },
            vitals = game.vitals.associate { it.id to it.short.ifBlank { it.name } }
        )
    }

    private fun refresh() {
        val ch = worldState.localCharacter ?: return
        content.clearChildren()
        content.top().left()
        content.defaults().left().pad(2f)
        when (tab) {
            CharacterTab.STATS -> buildStats(ch)
            CharacterTab.EQUIPMENT -> buildEquipment(ch)
            CharacterTab.SKILLS -> buildSkills(ch)
            CharacterTab.PROFICIENCY -> buildProficiency(ch)
        }
    }

    // ---- Stats ----

    private fun buildStats(ch: CharacterState) {
        val game = worldState.compiledGame ?: BlankGame.COMPILED
        val itemMap = items()
        val effective = ch.effectiveAttributes(game, itemMap)
        val paragonLevel = game.definition.paragon?.takeIf { it.enabled && ch.level >= game.definition.progression.maxLevel }?.level(ch) ?: 0
        val paragonText = if (paragonLevel > 0) "  Paragon $paragonLevel" else ""
        content.add(BagUi.label("${ch.name}  Level ${ch.level}$paragonText")).colspan(3).row()
        content.add(BagUi.label("XP ${worldState.visualXp(ch.xp)} / ${game.xpToNext(ch) ?: "max"}", Tone.MUTED)).colspan(3).padBottom(4f).row()
        for (v in game.definition.vitals) {
            val state = ch.vitals[v.id] ?: continue
            content.add(BagUi.label(v.short.ifBlank { v.name })).width(70f)
            content.add(BagUi.label("${state.cur} / ${state.max}")).colspan(2).left().row()
        }
        if (ch.unspentStatPoints > 0) content.add(BagUi.label("Unspent points: ${ch.unspentStatPoints}", Tone.GOOD)).colspan(3).padTop(4f).row()
        for (attr in game.definition.attributes) {
            val value = effective[attr.id] ?: 0
            val bonus = value - (ch.attributes[attr.id] ?: 0)
            content.add(BagUi.label(attr.short.ifBlank { attr.name })).width(70f)
            content.add(BagUi.label(value.toString() + if (bonus != 0) " (${if (bonus > 0) "+" else ""}$bonus gear)" else "", if (bonus > 0) Tone.GOOD else if (bonus < 0) Tone.BAD else Tone.NEUTRAL)).width(130f)
            if (ch.unspentStatPoints > 0 && attr.allocatable) {
                content.add(BagUi.button("+1") { netClient.send(ClientPacket.AllocateStatPoint(attr.id)) }).height(BagUi.buttonHeight)
            } else content.add()
            content.row()
        }
        val bp = game.definition.battlePower
        if (bp != null && bp.enabled) {
            content.add(BagUi.label("Battle Power")).width(70f)
            content.add(BagUi.label(bp.of(ch, game.definition, itemMap).toString(), Tone.GOOD)).colspan(2).row()
        }
        val nobility = game.definition.nobility
        if (nobility != null && nobility.enabled) {
            val donated = nobility.donated(ch)
            val rank = nobility.rankFor(donated)
            val next = nobility.nextRank(donated)
            content.add(BagUi.label("Nobility")).width(70f)
            content.add(BagUi.label(rank?.name ?: "Commoner", if (rank != null) Tone.GOOD else Tone.NEUTRAL)).width(130f)
            content.add(BagUi.label(if (next != null) "Next: ${next.name} at ${next.minDonation}" else "Max rank", Tone.MUTED)).row()
        }
    }

    // ---- Equipment: one slot per row, name wraps, Unequip always on the right ----

    private fun buildEquipment(ch: CharacterState) {
        val itemMap = items()
        val loot = worldState.gameDefinition?.loot
        val mainHandStack = ch.equipment.getEquipped(EquipmentSlot.WEAPON)
        val mainHandDef = mainHandStack?.let { itemMap[it.itemId] }
        val twoHanded = mainHandDef?.twoHanded == true
        val slotW = if (winW < 400f) 66f else 80f
        val buttonW = if (HudWindow.touchMode) 96f else 80f
        val nameW = (winW - 24f - slotW - buttonW - 40f).coerceAtLeast(80f)
        val gameSlots = worldState.gameDefinition?.slots ?: com.morgana.domain.game.EquipmentSlotDefinition.CLASSIC
        for (slotDef in gameSlots) {
            val slot = EquipmentSlot(slotDef.id)
            val stack = ch.equipment.getEquipped(slot)
            val def = stack?.let { itemMap[it.itemId] }
            content.add(BagUi.label(slotDef.name, Tone.MUTED)).width(slotW).top().padTop(6f)
            if (stack == null) {
                val text = if (slot == EquipmentSlot.SHIELD && twoHanded && mainHandDef != null && mainHandStack != null) {
                    "Occupied (${ItemRollText.displayName(mainHandDef, mainHandStack.rolls, loot, mainHandStack.plus)})"
                } else "-"
                content.add(BagUi.wrapped(text, Tone.MUTED)).width(nameW + buttonW + 28f).colspan(2).padTop(6f).row()
                continue
            }
            val nameCell = VisTable()
            nameCell.add(BagUi.icon(assets, def?.iconId ?: stack.itemId, 24f)).size(24f).padRight(4f).top()
            val shown = def?.let { ItemRollText.displayName(it, stack.rolls, loot, stack.plus) } ?: "Item #${stack.itemId}"
            nameCell.add(BagUi.wrapped(shown + if (stack.amount > 1) " x${stack.amount}" else "").also { l ->
                BagUi.hexColor(ItemRollText.color(stack.rolls, loot))?.let { l.color = it }
            }).width(nameW - 28f).left()
            content.add(nameCell).width(nameW + 28f).left()
            content.add(BagUi.button("Unequip") { netClient.send(ClientPacket.UnequipSlot(slot)) }).width(buttonW).height(BagUi.buttonHeight)
            content.row()
        }
    }

    // ---- Skills / spells: tap one for its details ----

    private fun buildSkills(ch: CharacterState) {
        val term = worldState.abilityTerminology()
        val spells = worldState.catalog.spells.associateBy { it.id }
        val known = ch.knownSpellIds.mapNotNull { spells[it] }
        if (known.isEmpty()) {
            content.add(BagUi.wrapped(com.morgana.client.ui.EmptyStates.abilities(term), Tone.MUTED)).width(winW - 60f).row()
            return
        }
        if (selectedSpell !in known.map { it.id }) selectedSpell = null
        for (spell in known) {
            val picked = spell.id == selectedSpell
            val row = VisTable().apply {
                touchable = Touchable.enabled
                if (picked) background = theme.cardBackground
                add(BagUi.label(spell.name, if (picked) Tone.GOOD else Tone.NEUTRAL)).growX().left().pad(4f)
                add(BagUi.label("Lv ${ch.spellLevel(spell.id)}", Tone.MUTED)).right().pad(4f)
                addListener(object : ClickListener() {
                    override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float) {
                        selectedSpell = if (picked) null else spell.id
                        refresh()
                    }
                })
            }
            content.add(row).growX().minHeight(BagUi.buttonHeight).row()
            if (picked) {
                val game = worldState.compiledGame ?: BlankGame.COMPILED
                val itemMap = items()
                val weaponType = ch.equipment.getEquipped(EquipmentSlot.WEAPON)?.let { itemMap[it.itemId]?.weaponType }.orEmpty()
                val tip = SpellInfo.tip(
                    spell, names(),
                    WearerInfo(ch.level, ch.effectiveAttributes(game, itemMap), ch.classId, equippedWeaponType = weaponType, weaponProficiencyLevels = ch.weaponProficiencyLevels),
                    vitals = ch.vitals.mapValues { it.value.cur },
                    spellLevel = ch.spellLevel(spell.id),
                    terminology = term
                )
                val textW = winW - 80f
                content.add(BagUi.label(tip.kind, Tone.MUTED)).left().padLeft(8f).row()
                if (tip.description.isNotBlank()) content.add(BagUi.wrapped(tip.description, Tone.MUTED)).width(textW).padLeft(8f).row()
                for (line in tip.lines) content.add(BagUi.wrapped(line.text, line.tone)).width(textW).padLeft(8f).row()
                content.add(BagUi.button("Quickbar") {
                    onAssignHotbar(HotbarSlotAction.CastSpell(spell.id, spell.name, spell.iconId))
                }).height(BagUi.buttonHeight).left().padLeft(8f).padTop(4f).padBottom(6f).row()
            }
        }
    }

    // ---- Weapon proficiency ----

    private fun buildProficiency(ch: CharacterState) {
        val game = worldState.compiledGame ?: BlankGame.COMPILED
        val wp = game.definition.weaponProficiency ?: return
        content.add(BagUi.wrapped("Using a weapon trains your skill with its type.", Tone.MUTED)).colspan(3).width(winW - 60f).padBottom(4f).row()
        for (wt in wp.weaponTypes) {
            val lv = ch.weaponProficiencyLevel(wt.id)
            val hits = ch.weaponProficiencyHitsFor(wt.id)
            val progress = if (lv >= wp.maxLevel) "MAX" else "$hits/${wp.hitsPerLevel * lv}"
            content.add(BagUi.label(wt.name)).width(110f)
            content.add(BagUi.label("Lv.$lv", if (lv > 1) Tone.GOOD else Tone.NEUTRAL)).width(50f)
            content.add(BagUi.label(progress, Tone.MUTED)).row()
        }
    }
}
