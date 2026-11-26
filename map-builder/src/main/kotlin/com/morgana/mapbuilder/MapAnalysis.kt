package com.morgana.mapbuilder

import kotlinx.serialization.Serializable

/**
 * What a map looks like in words and numbers, written next to the compiled map for tools that cannot see the picture (an LLM
 * placing camps, a script finding a flat clearing). Each row is one string of one character per tile: [terrain] rows use the letters
 * in [legend], [blocked] rows `#` for a tile nobody can walk onto, [occupied] rows `o` for a tile that holds a prop, cliff face or ramp.
 */
@Serializable
data class MapAnalysis(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val legend: Map<String, String>,
    val terrain: List<String>,
    val blocked: List<String>,
    val occupied: List<String>,
    /** `[npcId, x, y]` for every spawn the compiled map holds. */
    val spawns: List<List<Int>>
)
