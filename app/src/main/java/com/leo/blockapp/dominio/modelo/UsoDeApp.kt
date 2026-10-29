package com.leo.blockapp.dominio.modelo

data class UsoDeApp(
    val app: AppInstalada,
    val msHoy: Long,
)

sealed interface ResultadoUsoHoy {
    /** El usuario todavía no concedió el permiso de Acceso de uso. */
    data object SinPermiso : ResultadoUsoHoy

    /** Apps ordenadas de mayor a menor uso. */
    data class Listo(val apps: List<UsoDeApp>) : ResultadoUsoHoy
}
