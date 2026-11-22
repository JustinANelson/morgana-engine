package com.morgana.domain.script

/**
 * Permissions required by scripts to apply specific [ScriptEffect] mutations.
 * If a game declares a `capabilities` set in `game.json`, only effects matching
 * those capabilities are allowed to mutate simulation state.
 * If `capabilities` is null (default), all effects are permitted.
 */
object ScriptCapability {
    const val DAMAGE = "damage"
    const val HEAL = "heal"
    const val INVENTORY = "inventory"
    const val TELEPORT = "teleport"
    const val STATUS = "status"
    const val MESSAGE = "message"
    const val VISUAL = "visual"
    const val PROJECTILE = "projectile"
    const val PROGRESSION = "progression"
    const val VITAL = "vital"
    const val STATE = "state"

    val ALL: Set<String> = setOf(
        DAMAGE,
        HEAL,
        INVENTORY,
        TELEPORT,
        STATUS,
        MESSAGE,
        VISUAL,
        PROJECTILE,
        PROGRESSION,
        VITAL,
        STATE
    )

    /** Returns the capability required by this effect. */
    fun requiredCapability(effect: ScriptEffect): String = when (effect) {
        is ScriptEffect.DamagePlayer, is ScriptEffect.DamageNpc -> DAMAGE
        is ScriptEffect.HealPlayer, is ScriptEffect.HealNpc -> HEAL
        is ScriptEffect.GiveItem, is ScriptEffect.RemoveItem -> INVENTORY
        is ScriptEffect.WarpPlayer -> TELEPORT
        is ScriptEffect.ApplyStatus, is ScriptEffect.RemoveStatus -> STATUS
        is ScriptEffect.BroadcastMessage, is ScriptEffect.SendPlayerMessage -> MESSAGE
        is ScriptEffect.EmitFloatingText, is ScriptEffect.TriggerAnimation -> VISUAL
        is ScriptEffect.SpawnProjectile -> PROJECTILE
        is ScriptEffect.AwardExperience -> PROGRESSION
        is ScriptEffect.ConsumeVital -> VITAL
        is ScriptEffect.SetState -> STATE
    }
}

