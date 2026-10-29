package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.RestoreBlockResult

/** Copy shown after tapping "Deshacer" on a deleted block. */
object RestoreMessages {

    /** Final message: nothing could be restored, so there is nothing to retry (no trailing period). */
    const val NOT_RESTORED = "No se pudo restaurar"

    /** Prompt of the retry snackbar shown when the restore write failed. */
    const val RETRY_PROMPT = "No se pudo restaurar."

    /** The aggregated message for [result], or null when nothing needs to be said. */
    fun message(result: RestoreBlockResult): String? = when (result) {
        is RestoreBlockResult.Restored -> null
        is RestoreBlockResult.PartiallyRestored -> skippedMessage(result)
        is RestoreBlockResult.NotRestored -> NOT_RESTORED
    }

    private fun skippedMessage(result: RestoreBlockResult.PartiallyRestored): String {
        val skipped = result.skippedApps
        return if (skipped.size == 1) {
            "${skipped.first().label} ya está en otro bloqueo (${skipped.first().ownerBlock.name})"
        } else {
            "${LabelList.join(skipped.map { it.label })} ya están en otros bloqueos"
        }
    }
}
