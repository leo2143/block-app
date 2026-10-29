package com.leo.blockapp.domain.model

/** What the user wants to save. A null [id] means a new block. */
data class BlockDraft(
    val id: String?,
    val name: String,
    val packageNames: Set<String>,
    val rule: BlockRule,
)

enum class BlockViolation {
    NAME_BLANK,
    NAME_TOO_LONG,
    APPS_EMPTY,
    USAGE_LIMIT_OUT_OF_RANGE,
    COOLDOWN_OUT_OF_RANGE,
    DAILY_CYCLE_CAP_OUT_OF_RANGE,
    INACTIVITY_RESET_OUT_OF_RANGE,
}
