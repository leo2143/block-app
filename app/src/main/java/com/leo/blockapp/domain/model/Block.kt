package com.leo.blockapp.domain.model

/** A named group of apps sharing one rule. [packageNames] is insertion-ordered (selection order). */
data class Block(
    val id: String,
    val name: String,
    val packageNames: Set<String>,
    val rule: BlockRule,
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
