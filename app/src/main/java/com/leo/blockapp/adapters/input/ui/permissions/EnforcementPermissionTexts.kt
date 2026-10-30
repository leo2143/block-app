package com.leo.blockapp.adapters.input.ui.permissions

import com.leo.blockapp.adapters.input.ui.blocks.ListWarning

/** Spanish copy for the permissions that make blocks work, plus the Android 13 restricted-settings rule. */
internal object EnforcementPermissionTexts {

    const val CARD_TITLE = "Activa el bloqueo"
    const val CARD_BODY = "El servicio solo detecta qué app está abierta. No lee lo que ves en pantalla."
    const val OPEN_ACCESSIBILITY_SETTINGS = "Abrir ajustes de accesibilidad"
    const val OPEN_APP_INFO = "Información de la app"
    const val RESTRICTED_SETTINGS_INTRO = "En Android 13 o superior, si el interruptor está bloqueado:"

    val RESTRICTED_SETTINGS_STEPS = listOf(
        "1. Abre la información de la app.",
        "2. Toca el menú de tres puntos.",
        "3. Elige «Permitir ajustes restringidos».",
        "4. Vuelve a los ajustes de accesibilidad y activa el servicio.",
    )

    private const val RESTRICTED_SETTINGS_MIN_SDK = 33

    /** Sideloaded apps cannot enable an accessibility service until the user allows restricted settings (API 33+). */
    fun showsRestrictedSettingsSteps(sdkInt: Int): Boolean = sdkInt >= RESTRICTED_SETTINGS_MIN_SDK

    fun warningText(warning: ListWarning): String = when (warning) {
        ListWarning.ACCESSIBILITY_OFF ->
            "El bloqueo no está activo. Activa el servicio de accesibilidad para que tus bloqueos funcionen."
        ListWarning.USAGE_ACCESS_MISSING ->
            "Falta el permiso de acceso de uso. Sin él no se puede medir el tiempo de tus apps."
    }

    fun warningAction(warning: ListWarning): String = when (warning) {
        ListWarning.ACCESSIBILITY_OFF -> "Activar"
        ListWarning.USAGE_ACCESS_MISSING -> "Conceder"
    }
}
