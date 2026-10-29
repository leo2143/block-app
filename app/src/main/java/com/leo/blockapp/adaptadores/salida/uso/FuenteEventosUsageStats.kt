package com.leo.blockapp.adaptadores.salida.uso

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.leo.blockapp.dominio.modelo.EventoUso
import com.leo.blockapp.dominio.modelo.TipoEvento
import com.leo.blockapp.dominio.puertos.salida.FuenteEventosUso
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Adaptador de salida: traduce los eventos de UsageStatsManager al modelo del dominio. */
class FuenteEventosUsageStats @Inject constructor(
    @ApplicationContext private val context: Context,
) : FuenteEventosUso {

    override suspend fun eventos(desde: Long, hasta: Long): List<EventoUso> = withContext(Dispatchers.IO) {
        val usageStats = context.getSystemService(UsageStatsManager::class.java)
        val consulta = usageStats.queryEvents(desde, hasta)
        val evento = UsageEvents.Event()
        val resultado = mutableListOf<EventoUso>()

        while (consulta.hasNextEvent()) {
            consulta.getNextEvent(evento)
            val tipo = when (evento.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> TipoEvento.AL_FRENTE
                UsageEvents.Event.ACTIVITY_PAUSED -> TipoEvento.AL_FONDO
                else -> continue
            }
            resultado += EventoUso(evento.packageName, evento.className.orEmpty(), tipo, evento.timeStamp)
        }
        resultado
    }
}
