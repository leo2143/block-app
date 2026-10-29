package com.leo.blockapp.adapters.input.ui.blocks

enum class MinutesError { BLANK, NOT_A_NUMBER, OVERFLOW }

/** A numeric draft kept as typed text; [value] is set only when [error] is null. */
data class MinutesField(
    val text: String,
    val value: Int?,
    val error: MinutesError?,
)

object MinutesInput {

    fun parse(text: String): MinutesField {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return MinutesField(text, value = null, error = MinutesError.BLANK)
        // ASCII only: Char.isDigit() accepts other scripts that toIntOrNull() then rejects.
        if (!trimmed.all { it in '0'..'9' }) return MinutesField(text, value = null, error = MinutesError.NOT_A_NUMBER)

        val value = trimmed.toIntOrNull()
            ?: return MinutesField(text, value = null, error = MinutesError.OVERFLOW)
        return MinutesField(text, value = value, error = null)
    }

    fun of(minutes: Int): MinutesField = MinutesField(text = minutes.toString(), value = minutes, error = null)
}
