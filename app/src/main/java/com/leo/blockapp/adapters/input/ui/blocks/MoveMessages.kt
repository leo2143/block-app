package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.MoveConflict

/** Copy for the "move apps from other blocks" confirmation dialog. */
object MoveMessages {

    private const val USAGE_RESET_FOOTER = "Su uso se reinicia."

    /** One move line per source block (plus a deletion line when it ends up empty) and one footer. */
    fun build(conflicts: List<MoveConflict>): List<String> {
        if (conflicts.isEmpty()) return emptyList()

        val lines = conflicts.groupBy { it.fromBlockId }.values.flatMap { fromOneSource ->
            val sourceName = fromOneSource.first().fromBlockName
            val moveLine = "¿Mover ${LabelList.join(fromOneSource.map { it.appLabel })} de $sourceName a este bloqueo?"
            if (fromOneSource.any { it.fromBlockWillBeDeleted }) {
                listOf(moveLine, "$sourceName se eliminará porque quedará sin apps.")
            } else {
                listOf(moveLine)
            }
        }
        return lines + USAGE_RESET_FOOTER
    }
}
