package com.morgana.domain.sim

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.map.MapDefinition

/** What a [WarpRouter] decides about a warp. */
sealed interface WarpRoute {
    /** The player goes to [map] instead of the target (a private copy of it, say). The map must already be registered. */
    data class To(val map: MapDefinition) : WarpRoute

    /** Treat the warp as one to [mapId] instead and ask the routers again. The tile stays the same (clamped to the new map). */
    data class Redirect(val mapId: Int) : WarpRoute

    /** The warp does not happen. The router has told the player why. */
    data object Refused : WarpRoute
}

/**
 * The warp seam (ADR 0018): decides where a warp really goes. Every way a player is moved to a map (warp tiles, warp spells and
 * scrolls, the event warp step, a script warp) asks the routers first, in install order; the first one with an opinion wins and a
 * warp nobody has an opinion about goes to the map it named. Instanced maps work this way: `InstancingSystem` routes a warp
 * into a template map to the private copy for the player's party. A router may create maps (`registerMap`) and tell the player
 * why it refused, but must not move the player: the kernel does that with the route it is given.
 */
interface WarpRouter {
    /** The route for [player] warping to [target] (tile [tileX], [tileY]), or null for no opinion. */
    fun route(
        sim: WorldSimulation, player: CharacterState, target: MapDefinition, tileX: Int, tileY: Int, out: MutableList<WorldSimulationEvent>
    ): WarpRoute?
}

/** How many redirects one warp may follow before it is refused as a loop. */
private const val MAX_WARP_REDIRECTS = 4

/** The map [player] actually goes to for a warp to [target], or null when the warp is refused (or loops). */
internal fun WorldSimulation.routedMap(
    player: CharacterState, target: MapDefinition, tileX: Int, tileY: Int, out: MutableList<WorldSimulationEvent>
): MapDefinition? {
    var current = target
    repeat(MAX_WARP_REDIRECTS + 1) {
        val route = warpRouters.firstNotNullOfOrNull { if (isOn(it)) it.route(this, player, current, tileX, tileY, out) else null }
            ?: return current
        when (route) {
            is WarpRoute.To -> return route.map
            WarpRoute.Refused -> return null
            is WarpRoute.Redirect -> current = maps[route.mapId] ?: return null
        }
    }
    return null
}
