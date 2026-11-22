---
type: Simulation System
title: "Pets"
description: "Companions a player adopts with an item, summons with /pet, who follow across maps, fight for the owner and grow with their XP."
tags: [pets, companions, combat]
code: ["core-domain/src/main/kotlin/com/morgana/domain/content/PetTraits.kt", "core-domain/src/main/kotlin/com/morgana/domain/systems/PetSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/entity/EntityAndPlayerModels.kt", "core-domain/src/test/kotlin/com/morgana/domain/PetTest.kt"]
symbols: ["PetTraits", "PetState", "adoptPetNpcId", "adoptPetFromItem", "petCommand", "tickPets", "grantPetXp", "petDamage", "activePetNpcId", "ownerId", "displayName"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-19T20:00:00Z }
stale_after: 2027-05-18T00:00:00Z
---

# Pets

A pet is an ordinary [NPC](/systems/combat.md) with a `pet` section (`PetTraits`) and behavior `FRIENDLY_ROAM`, so monsters and players leave it alone (content validation insists on that). Nothing else about the game changes for games without pets. The behaviour is installed as `PetSystem` (`systems/PetSystem.kt`): a tick participant, a kill observer (the pet earns experience beside its owner), an item use handler (a pet item adopts its pet) and the `/pet` chat command, all through the [system seams](/systems/system_seams.md).

**Adopting**: a consumable item with `adoptPetNpcId` is used from the bag (`adoptPetFromItem`): it is used up, the pet joins `CharacterState.pets` (`PetState`: level, xp, name) and becomes the active pet. A character who already has that pet keeps the item and is told. `ItemDefinition.adoptPetNpcId` must name an NPC with a `pet` section (validated).

**Commands** (`petCommand`, chat): `/pet` lists pets, `/pet summon <name or id>` makes one active, `/pet dismiss` sends it home, `/pet name <new name>` renames the active pet (up to 16 characters; shown over its head through `MapNpcInstance.displayName`).

**The live pet** (`tickPets`, every tick): the active pet is an NPC instance `pet-<owner id>` (`MapNpcInstance.ownerId`, no spawn slot) in the owner's map list, so every client already draws it. It is created beside the owner, follows to within `followDistance` tiles (stepping around obstacles, including its owner), is moved to the new map when the owner changes map and re-placed when the owner gets far away, and removed when it is dismissed, swapped or the owner leaves. The monster AI skips it (`ownerId`), and the boss-add cleanup leaves it alone.

**Fighting** (`fights`): the pet picks the nearest monster within 8 tiles that is hunting its owner, walks up and strikes every `attackSpeedTicks` for `attackDamage` grown by `damagePerLevelPercent` per level above the first. The strike goes through `applyDamageToNpc` as the owner, so threat, XP, loot and quest progress are the owner's.

**Growing** (`grantPetXp`, called when the owner kills something): the active pet earns `xpSharePercent` of the XP the kill gave the owner; going from level L to L + 1 needs `L * xpPerLevel`, up to `maxLevel`. Level-ups are announced.

Halcyon Reach's pets (a Tame Finch, Lantern Sprite, Mistfur Cub and Ember Whelp, items 118 to 121) are sold by the Pet Handler in Harrow Cross. The client has a Pets window (`PetsWindow`, `PetsModel`; menu entry "Pets" when the game has any pet NPC) that lists each pet with its level and XP and sends the `/pet summon`, `/pet dismiss` and `/pet name` commands. Pet art of their own and mount sprites are still open.
