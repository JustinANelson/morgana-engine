package com.morgana.domain.validation

import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.contentMaps

/** Snapshot of the simulation's live content definitions as a validatable [ContentCatalog]. */
fun WorldSimulation.toContentCatalog(): ContentCatalog = ContentCatalog(
    maps = contentMaps(),
    classes = classes.toMap(),
    items = items.toMap(),
    npcs = npcs.toMap(),
    spells = spells.toMap(),
    shops = shops.toMap(),
    resources = resources.toMap(),
    recipes = recipes.toMap(),
    quests = quests.toMap(),
    events = events.toMap(),
    commonEvents = commonEvents.toMap(),
    animations = animations.toMap(),
    actionSkills = actionSkills.toMap(),
    weaponSets = weaponSets.toMap()
)
