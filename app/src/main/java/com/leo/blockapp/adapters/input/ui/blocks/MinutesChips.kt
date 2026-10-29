package com.leo.blockapp.adapters.input.ui.blocks

/** Quick-pick values shown as chips next to the free-text "Otro" input. */
object MinutesChips {

    val LIMIT = listOf(5, 10, 15, 30, 60, 90)

    /** Starts at the minimum allowed cooldown (15). */
    val COOLDOWN = listOf(15, 30, 60, 90)

    /** True when [field] is not one of [presets], so "Otro" is the chip that stands for it. */
    fun isCustom(field: MinutesField, presets: List<Int>): Boolean = field.value == null || field.value !in presets
}
