package com.leo.blockapp.dominio.puertos.salida

/** Puerto de salida: permisos especiales que el usuario concede desde Ajustes. */
interface PermisosSistema {
    fun tieneAccesoDeUso(): Boolean
}
