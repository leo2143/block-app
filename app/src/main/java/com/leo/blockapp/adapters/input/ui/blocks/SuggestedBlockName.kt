package com.leo.blockapp.adapters.input.ui.blocks

/**
 * Builds the default block name from the labels of the selected apps, in selection order.
 *
 * The limit is measured with [String.length], the same measure the domain validator uses for
 * the name, so a suggestion never trips NAME_TOO_LONG.
 */
object SuggestedBlockName {

    const val MAX_LENGTH = 40
    private const val ELLIPSIS = "…"

    fun from(labelsInSelectionOrder: List<String>): String {
        val suggestion = when (labelsInSelectionOrder.size) {
            0 -> ""
            1 -> labelsInSelectionOrder[0]
            2 -> "${labelsInSelectionOrder[0]} y ${labelsInSelectionOrder[1]}"
            else -> {
                val remaining = labelsInSelectionOrder.size - 2
                "${labelsInSelectionOrder[0]}, ${labelsInSelectionOrder[1]} y $remaining más"
            }
        }
        return truncate(suggestion)
    }

    private fun truncate(text: String): String =
        if (text.length <= MAX_LENGTH) text else text.take(MAX_LENGTH - ELLIPSIS.length) + ELLIPSIS
}
