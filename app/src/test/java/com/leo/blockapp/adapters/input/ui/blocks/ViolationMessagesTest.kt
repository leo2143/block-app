package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.BlockViolation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ViolationMessagesTest {

    @Test
    fun `every violation has non blank copy`() {
        BlockViolation.entries.forEach { violation ->
            assertFalse("$violation has blank copy", ViolationMessages.message(violation).isBlank())
        }
    }

    @Test
    fun `every minutes error has non blank copy`() {
        MinutesError.entries.forEach { error ->
            assertFalse("$error has blank copy", ViolationMessages.message(error).isBlank())
        }
    }

    @Test
    fun `violation copy is exact`() {
        val expected = mapOf(
            BlockViolation.NAME_BLANK to "Escribe un nombre para el bloqueo",
            BlockViolation.NAME_TOO_LONG to "El nombre admite hasta 40 caracteres",
            BlockViolation.APPS_EMPTY to "Elige al menos una app",
            BlockViolation.USAGE_LIMIT_OUT_OF_RANGE to "El límite debe estar entre 1 y 1440 min",
            BlockViolation.COOLDOWN_OUT_OF_RANGE to "El enfriamiento mínimo es 15 min (máximo 1440)",
            BlockViolation.DAILY_CYCLE_CAP_OUT_OF_RANGE to "El tope diario debe estar entre 1 y 20 ciclos",
            BlockViolation.INACTIVITY_RESET_OUT_OF_RANGE to "La inactividad debe estar entre 1 y 1440 min",
        )

        assertEquals(BlockViolation.entries.toSet(), expected.keys)
        expected.forEach { (violation, copy) -> assertEquals(copy, ViolationMessages.message(violation)) }
    }

    @Test
    fun `minutes error copy is exact`() {
        assertEquals("Escribe un número", ViolationMessages.message(MinutesError.BLANK))
        assertEquals("Solo se permiten números", ViolationMessages.message(MinutesError.NOT_A_NUMBER))
        assertEquals("El número es demasiado grande", ViolationMessages.message(MinutesError.OVERFLOW))
    }
}
