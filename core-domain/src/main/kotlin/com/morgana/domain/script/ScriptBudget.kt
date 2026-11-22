package com.morgana.domain.script

import kotlinx.serialization.Serializable

/**
 * Deterministic execution and memory limits applied to script calls.
 *
 * All execution limits are based on instruction counts counted by the runtime on the running thread,
 * ensuring reproducible, deterministic behavior across replays and restarts.
 */
@Serializable
data class ScriptBudget(
    /** Max instructions allowed for pure formula calculations (e.g. melee, spell output). */
    val formulaInstructions: Long = 100_000L,

    /** Max instructions allowed for chat/slash command handlers. */
    val commandInstructions: Long = 500_000L,

    /** Max instructions allowed for event commands and conditions. */
    val eventInstructions: Long = 1_000_000L,

    /** Max instructions allowed for tick hooks and combat actions. */
    val actionInstructions: Long = 2_000_000L,

    /** Max memory ceiling in bytes allocated within a single script lane (default: 8 MB). */
    val memoryCeilingBytes: Long = 8L * 1024 * 1024
)

