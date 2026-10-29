package com.leo.blockapp.domain.blocks

import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockViolation

/** Pure validation of a draft. Reports every violation at once, never throws. */
object BlockValidator {

    const val MAX_NAME_LENGTH = 40
    private val usageLimitRange = 1..1440
    private val cooldownRange = 15..1440
    private val inactivityResetRange = 1..1440
    private val dailyCycleCapRange = 1..20

    fun validate(draft: BlockDraft): Set<BlockViolation> {
        val violations = mutableSetOf<BlockViolation>()

        val trimmedName = draft.name.trim()
        if (trimmedName.isBlank()) violations += BlockViolation.NAME_BLANK
        if (trimmedName.length > MAX_NAME_LENGTH) violations += BlockViolation.NAME_TOO_LONG
        if (draft.packageNames.isEmpty()) violations += BlockViolation.APPS_EMPTY

        when (val rule = draft.rule) {
            is BlockRule.AccumulatedUsageWithCooldown -> {
                if (rule.usageLimitMinutes !in usageLimitRange) violations += BlockViolation.USAGE_LIMIT_OUT_OF_RANGE
                if (rule.cooldownMinutes !in cooldownRange) violations += BlockViolation.COOLDOWN_OUT_OF_RANGE
                val dailyCycleCap = rule.dailyCycleCap
                if (dailyCycleCap != null && dailyCycleCap !in dailyCycleCapRange) {
                    violations += BlockViolation.DAILY_CYCLE_CAP_OUT_OF_RANGE
                }
                val inactivityResetMinutes = rule.inactivityResetMinutes
                if (inactivityResetMinutes != null && inactivityResetMinutes !in inactivityResetRange) {
                    violations += BlockViolation.INACTIVITY_RESET_OUT_OF_RANGE
                }
            }
        }
        return violations
    }
}
