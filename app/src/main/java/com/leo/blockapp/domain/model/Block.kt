package com.leo.blockapp.domain.model

/**
 * A named group of apps sharing one rule. [packageNames] is insertion-ordered (selection order). [position] is the
 * stable, user-visible order key: stored blocks are always sorted by it ascending, a new block takes the highest
 * position plus one, and editing, moving apps or deleting other blocks never changes it.
 */
data class Block(
    val id: String,
    val name: String,
    val packageNames: Set<String>,
    val rule: BlockRule,
    val position: Long,
    val enabled: Boolean = true,
)

sealed interface BlockRule {
    data class AccumulatedUsageWithCooldown(
        val usageLimitMinutes: Int,
        val cooldownMinutes: Int,
        val dailyCycleCap: Int? = null,
        val inactivityResetMinutes: Int? = null,
    ) : BlockRule
}
