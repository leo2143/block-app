package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.BlockViolation

/** The editor inputs a violation can be attached to. A violation is visible once its field is touched. */
enum class EditorField { NAME, APPS, LIMIT, COOLDOWN, CAP, INACTIVITY }

fun BlockViolation.field(): EditorField = when (this) {
    BlockViolation.NAME_BLANK, BlockViolation.NAME_TOO_LONG -> EditorField.NAME
    BlockViolation.APPS_EMPTY -> EditorField.APPS
    BlockViolation.USAGE_LIMIT_OUT_OF_RANGE -> EditorField.LIMIT
    BlockViolation.COOLDOWN_OUT_OF_RANGE -> EditorField.COOLDOWN
    BlockViolation.DAILY_CYCLE_CAP_OUT_OF_RANGE -> EditorField.CAP
    BlockViolation.INACTIVITY_RESET_OUT_OF_RANGE -> EditorField.INACTIVITY
}
