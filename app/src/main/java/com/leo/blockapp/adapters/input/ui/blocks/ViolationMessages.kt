package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.BlockViolation

/** Spanish copy for validation violations and numeric parse errors. Exhaustive on purpose. */
object ViolationMessages {

    fun message(violation: BlockViolation): String = when (violation) {
        BlockViolation.NAME_BLANK -> "Escribe un nombre para el bloqueo"
        BlockViolation.NAME_TOO_LONG -> "El nombre admite hasta 40 caracteres"
        BlockViolation.APPS_EMPTY -> "Elige al menos una app"
        BlockViolation.USAGE_LIMIT_OUT_OF_RANGE -> "El límite debe estar entre 1 y 1440 min"
        BlockViolation.COOLDOWN_OUT_OF_RANGE -> "El enfriamiento mínimo es 15 min (máximo 1440)"
        BlockViolation.DAILY_CYCLE_CAP_OUT_OF_RANGE -> "El tope diario debe estar entre 1 y 20 ciclos"
        BlockViolation.INACTIVITY_RESET_OUT_OF_RANGE -> "La inactividad debe estar entre 1 y 1440 min"
    }

    fun message(error: MinutesError): String = when (error) {
        MinutesError.BLANK -> "Escribe un número"
        MinutesError.NOT_A_NUMBER -> "Solo se permiten números"
        MinutesError.OVERFLOW -> "El número es demasiado grande"
    }
}
