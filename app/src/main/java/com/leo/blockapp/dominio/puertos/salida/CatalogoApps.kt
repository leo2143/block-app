package com.leo.blockapp.dominio.puertos.salida

import com.leo.blockapp.dominio.modelo.AppInstalada

/** Puerto de salida: qué apps hay instaladas que el usuario pueda abrir. */
interface CatalogoApps {
    suspend fun appsInstaladas(): List<AppInstalada>
}
