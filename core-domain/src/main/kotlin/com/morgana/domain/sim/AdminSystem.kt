package com.morgana.domain.sim

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.access.PermissionFlag
import com.morgana.domain.content.AnimationDefinition
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import kotlin.math.max

/**
 * Admin slash commands and live (F1) content hot-swaps.
 *
 * Implemented as internal extension functions over [WorldSimulation] state;
 * the public API is exposed by thin delegating methods on [WorldSimulation].
 */

internal fun WorldSimulation.executeAdminSlashCommandImpl(adminCharacterId: String, rawCommandLine: String): List<WorldSimulationEvent> {
    val admin = players[adminCharacterId] ?: return emptyList()
    val parts = rawCommandLine.trim().removePrefix("/").split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return emptyList()
    val cmd = parts[0].lowercase()
    val out = mutableListOf<WorldSimulationEvent>()

    // Commands that belong to a system (/arena, /pet, /guildwar, /stall) are carried out by that system.
    if (runChatCommand(admin, parts, out)) return out

    when (cmd) {
        // A player command, not an admin one: a ghost goes home.
        "release" -> return releaseGhostImpl(adminCharacterId)
        "revive" -> return selfReviveImpl(adminCharacterId)
        "warpto" -> {
            if (!admin.accessLevel.hasPermission(PermissionFlag.WARP_TO_MAP)) return emptyList()
            val targetMapId = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
            val destMap = maps[targetMapId] ?: return emptyList()
            val oldMap = admin.position.mapId
            val tx = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, destMap.width - 1) ?: (destMap.width / 2)
            val ty = parts.getOrNull(3)?.toIntOrNull()?.coerceIn(0, destMap.height - 1) ?: (destMap.height / 2)
            val updated = admin.copy(
                position = WorldPosition.fromTile(destMap.id, tx, ty, Direction.DOWN, destMap.tileSize)
            )
            players[adminCharacterId] = updated
            out.add(WorldSimulationEvent.PlayerMoved(updated, oldMap, destMap.id))
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        "warpmeto" -> {
            if (!admin.accessLevel.hasPermission(PermissionFlag.WARP_TO_PLAYER)) return emptyList()
            val targetName = parts.getOrNull(1) ?: return emptyList()
            val targetPlayer = players.values.firstOrNull { it.name.equals(targetName, ignoreCase = true) } ?: return emptyList()
            val oldMap = admin.position.mapId
            val updated = admin.copy(position = targetPlayer.position)
            players[adminCharacterId] = updated
            out.add(WorldSimulationEvent.PlayerMoved(updated, oldMap, updated.position.mapId))
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        "warptome" -> {
            if (!admin.accessLevel.hasPermission(PermissionFlag.SUMMON_PLAYER)) return emptyList()
            val targetName = parts.getOrNull(1) ?: return emptyList()
            val targetPlayer = players.values.firstOrNull { it.name.equals(targetName, ignoreCase = true) } ?: return emptyList()
            val oldMap = targetPlayer.position.mapId
            val updated = targetPlayer.copy(position = admin.position)
            players[targetPlayer.id] = updated
            out.add(WorldSimulationEvent.PlayerMoved(updated, oldMap, updated.position.mapId))
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        "item", "spawnitem" -> {
            if (!admin.accessLevel.hasPermission(PermissionFlag.SPAWN_ITEM)) return emptyList()
            val itemId = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
            val amount = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val itemDef = items[itemId] ?: return emptyList()
            val mut = admin.inventory.addItem(itemDef, amount)
            val updated = admin.copy(inventory = mut.updatedInventory)
            players[adminCharacterId] = updated
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        "setaccess" -> {
            if (!admin.accessLevel.hasPermission(PermissionFlag.SET_ACCESS)) return emptyList()
            val targetName = parts.getOrNull(1) ?: return emptyList()
            val rank = parts.getOrNull(2)?.toIntOrNull() ?: return emptyList()
            val targetPlayer = players.values.firstOrNull { it.name.equals(targetName, ignoreCase = true) } ?: return emptyList()
            val newAccess = AccessLevel.fromRank(rank)
            val updated = targetPlayer.copy(accessLevel = newAccess)
            players[targetPlayer.id] = updated
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        "kick" -> {
            if (!admin.accessLevel.hasPermission(PermissionFlag.KICK_MUTE)) return emptyList()
            val targetName = parts.getOrNull(1) ?: return emptyList()
            val targetPlayer = players.values.firstOrNull { it.name.equals(targetName, ignoreCase = true) } ?: return emptyList()
            out.add(WorldSimulationEvent.PlayerModerated(targetPlayer.id, "KICK", "Kicked by ${admin.name}"))
        }
        "heal" -> {
            if (!admin.accessLevel.hasAtLeast(AccessLevel.MAPPER)) return emptyList()
            val fullVitals = admin.vitals.mapValues { (_, v) -> v.copy(cur = v.max) }
            val updated = admin.copy(vitals = fullVitals)
            players[adminCharacterId] = updated
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        else -> {
            val scriptCmd = scripts.registry.getCommand(cmd)
            if (scriptCmd != null && admin.accessLevel.rank >= scriptCmd.minAccessLevel) {
                val worldView = asScriptWorldView()
                val snapshot = worldView.getPlayer(adminCharacterId)
                if (snapshot != null) {
                    val result = scriptCmd.handler(snapshot, parts.drop(1), worldView)
                    if (result.handled) {
                        if (result.feedbackMessage != null) {
                            out.add(
                                WorldSimulationEvent.ChatEmitted(
                                    channel = "SYSTEM",
                                    mapId = admin.position.mapId,
                                    senderId = "SYSTEM",
                                    senderName = "SYSTEM",
                                    targetCharacterId = adminCharacterId,
                                    message = result.feedbackMessage,
                                    showOverheadBubble = false
                                )
                            )
                        }
                        result.effects.forEach { eff ->
                            out.addAll(applyScriptEffect(eff))
                        }
                    }
                }
            }
        }
    }
    return out
}

internal fun WorldSimulation.applyLiveMapEditImpl(editorCharacterId: String, updatedMap: MapDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_MAP)) return emptyList()
    val bumped = updatedMap.copy(revision = max(updatedMap.revision, (maps[updatedMap.id]?.revision ?: 0) + 1))
    registerMap(bumped)
    return listOf(WorldSimulationEvent.MapHotSwapped(bumped))
}

internal fun WorldSimulation.applyLiveItemEditImpl(editorCharacterId: String, item: ItemDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    items[item.id] = item
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Item #${item.id}: ${item.name}"))
}

internal fun WorldSimulation.applyLiveNpcEditImpl(editorCharacterId: String, npc: NpcDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    npcs[npc.id] = npc
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved NPC #${npc.id}: ${npc.name}"))
}

internal fun WorldSimulation.applyLiveSpellEditImpl(editorCharacterId: String, spell: SpellDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    spells[spell.id] = spell
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Spell #${spell.id}: ${spell.name}"))
}

internal fun WorldSimulation.applyLiveShopEditImpl(editorCharacterId: String, shop: ShopDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    shops[shop.id] = shop
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Shop #${shop.id}: ${shop.name}"))
}

internal fun WorldSimulation.applyLiveResourceEditImpl(editorCharacterId: String, resource: ResourceDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    resources[resource.id] = resource
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Resource #${resource.id}: ${resource.name}"))
}

internal fun WorldSimulation.applyLiveActionSkillEditImpl(editorCharacterId: String, skill: com.morgana.domain.content.ActionSkillDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    actionSkills[skill.id] = skill
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Action skill #${skill.id}: ${skill.name}"))
}

internal fun WorldSimulation.applyLiveWeaponSetEditImpl(editorCharacterId: String, weaponSet: com.morgana.domain.content.WeaponSetDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    weaponSets[weaponSet.id] = weaponSet
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Weapon set #${weaponSet.id}: ${weaponSet.name}"))
}

internal fun WorldSimulation.applyLiveClassEditImpl(editorCharacterId: String, classDefinition: com.morgana.domain.content.ClassDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    classes[classDefinition.id] = classDefinition
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Class #${classDefinition.id}: ${classDefinition.name}"))
}

internal fun WorldSimulation.applyLiveRecipeEditImpl(editorCharacterId: String, recipe: RecipeDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    recipes[recipe.id] = recipe
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Recipe #${recipe.id}: ${recipe.name}"))
}

internal fun WorldSimulation.applyLiveQuestEditImpl(editorCharacterId: String, quest: QuestDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    quests[quest.id] = quest
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Quest #${quest.id}: ${quest.name}"))
}

internal fun WorldSimulation.applyLiveEventEditImpl(editorCharacterId: String, event: EventDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    events[event.id] = event
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Event #${event.id}: ${event.name}"))
}

internal fun WorldSimulation.applyLiveCommonEventEditImpl(editorCharacterId: String, commonEvent: CommonEventDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    commonEvents[commonEvent.id] = commonEvent
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Common Event #${commonEvent.id}: ${commonEvent.name}"))
}

internal fun WorldSimulation.applyLiveAnimationEditImpl(editorCharacterId: String, animation: AnimationDefinition): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    animations[animation.id] = animation
    return listOf(WorldSimulationEvent.ContentCatalogHotSwapped("Saved Animation #${animation.id}: ${animation.name}"))
}
