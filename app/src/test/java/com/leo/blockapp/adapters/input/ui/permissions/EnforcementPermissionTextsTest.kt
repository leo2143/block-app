package com.leo.blockapp.adapters.input.ui.permissions

import com.leo.blockapp.adapters.input.ui.blocks.ListWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementPermissionTextsTest {

    @Test
    fun `the restricted settings steps appear from Android 13`() {
        assertFalse(EnforcementPermissionTexts.showsRestrictedSettingsSteps(sdkInt = 29))
        assertFalse(EnforcementPermissionTexts.showsRestrictedSettingsSteps(sdkInt = 32))
        assertTrue(EnforcementPermissionTexts.showsRestrictedSettingsSteps(sdkInt = 33))
        assertTrue(EnforcementPermissionTexts.showsRestrictedSettingsSteps(sdkInt = 35))
    }

    @Test
    fun `the steps follow the order of the system screens`() {
        assertEquals(
            listOf(
                "1. Abre la información de la app.",
                "2. Toca el menú de tres puntos.",
                "3. Elige «Permitir ajustes restringidos».",
                "4. Vuelve a los ajustes de accesibilidad y activa el servicio.",
            ),
            EnforcementPermissionTexts.RESTRICTED_SETTINGS_STEPS,
        )
    }

    @Test
    fun `the card explains what the service does`() {
        assertEquals("Activa el bloqueo", EnforcementPermissionTexts.CARD_TITLE)
        assertEquals(
            "El servicio solo detecta qué app está abierta. No lee lo que ves en pantalla.",
            EnforcementPermissionTexts.CARD_BODY,
        )
        assertEquals("Abrir ajustes de accesibilidad", EnforcementPermissionTexts.OPEN_ACCESSIBILITY_SETTINGS)
        assertEquals("Información de la app", EnforcementPermissionTexts.OPEN_APP_INFO)
    }

    @Test
    fun `the list warnings are in Spanish with an action`() {
        assertEquals(
            "El bloqueo no está activo. Activa el servicio de accesibilidad para que tus bloqueos funcionen.",
            EnforcementPermissionTexts.warningText(ListWarning.ACCESSIBILITY_OFF),
        )
        assertEquals("Activar", EnforcementPermissionTexts.warningAction(ListWarning.ACCESSIBILITY_OFF))
        assertEquals(
            "Falta el permiso de acceso de uso. Sin él no se puede medir el tiempo de tus apps.",
            EnforcementPermissionTexts.warningText(ListWarning.USAGE_ACCESS_MISSING),
        )
        assertEquals("Conceder", EnforcementPermissionTexts.warningAction(ListWarning.USAGE_ACCESS_MISSING))
    }
}
