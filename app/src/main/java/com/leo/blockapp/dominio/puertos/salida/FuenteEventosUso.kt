package com.leo.blockapp.dominio.puertos.salida

import com.leo.blockapp.dominio.modelo.EventoUso

/** Puerto de salida: de dónde salen los eventos crudos de uso (en Android, UsageStatsManager). */
interface FuenteEventosUso {
    suspend fun eventos(desde: Long, hasta: Long): List<EventoUso>
}
