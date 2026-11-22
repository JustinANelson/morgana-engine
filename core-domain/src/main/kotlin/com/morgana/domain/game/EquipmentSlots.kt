package com.morgana.domain.game

import com.morgana.domain.item.EquipmentSlot
import kotlinx.serialization.Serializable

/**
 * One equipment slot of a game. Without an `equipmentSlots` section in `game.json` a game has the nine classic slots
 * ([CLASSIC]); with one, exactly the listed slots exist, so a game can add `GARMENT`, `STEED` or `BACKPACK`, drop `AMMO`,
 * or rename what the Character window shows. Items choose a slot with `ItemDefinition.equipSlot`.
 *
 * Combat reads `WEAPON` (damage, ammunition type and proficiency); a game must keep it. `SHIELD` (two-handed weapons) and
 * `AMMO` (arrows) work only when the game keeps them.
 */
@Serializable
data class EquipmentSlotDefinition(
    /** Upper-case id such as `GARMENT`; matches `ItemDefinition.equipSlot`. */
    val id: String,
    /** Label in the Character window. */
    val name: String
) {
    companion object {
        val CLASSIC: List<EquipmentSlotDefinition> = listOf(
            EquipmentSlotDefinition("WEAPON", "Main Hand"),
            EquipmentSlotDefinition("SHIELD", "Off-Hand"),
            EquipmentSlotDefinition("HELMET", "Helmet"),
            EquipmentSlotDefinition("ARMOR", "Armor"),
            EquipmentSlotDefinition("BOOTS", "Boots"),
            EquipmentSlotDefinition("RING", "Ring"),
            EquipmentSlotDefinition("AMULET", "Amulet"),
            EquipmentSlotDefinition("TOOL", "Tool"),
            EquipmentSlotDefinition("AMMO", "Ammo")
        )
    }
}
