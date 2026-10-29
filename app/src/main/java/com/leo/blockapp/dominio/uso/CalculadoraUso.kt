package com.leo.blockapp.dominio.uso

import com.leo.blockapp.dominio.modelo.EventoUso
import com.leo.blockapp.dominio.modelo.TipoEvento

/**
 * Convierte eventos de "al frente / al fondo" en tiempo de uso por app.
 *
 * Una app está en primer plano mientras al menos una de sus actividades lo esté, así que
 * pasar de una pantalla a otra dentro de la misma app no corta ni duplica el tiempo.
 */
object CalculadoraUso {

    /** Milisegundos que cada app estuvo en primer plano dentro de [desde, hasta]. */
    fun tiempoEnPrimerPlano(eventos: List<EventoUso>, desde: Long, hasta: Long): Map<String, Long> {
        val totales = mutableMapOf<String, Long>()
        val actividadesAlFrente = mutableMapOf<String, MutableSet<String>>()
        val inicioSesion = mutableMapOf<String, Long>()
        val vistos = mutableSetOf<String>()

        fun sumar(paquete: String, inicio: Long, fin: Long) {
            if (fin > inicio) totales[paquete] = (totales[paquete] ?: 0L) + (fin - inicio)
        }

        for (e in eventos.sortedBy { it.momento }) {
            if (e.momento < desde || e.momento > hasta) continue
            val alFrente = actividadesAlFrente.getOrPut(e.paquete) { mutableSetOf() }
            val primerEvento = vistos.add(e.paquete)

            when (e.tipo) {
                TipoEvento.AL_FRENTE -> {
                    if (alFrente.isEmpty()) inicioSesion[e.paquete] = e.momento
                    alFrente += e.actividad
                }
                TipoEvento.AL_FONDO -> {
                    if (alFrente.remove(e.actividad)) {
                        if (alFrente.isEmpty()) sumar(e.paquete, inicioSesion.remove(e.paquete)!!, e.momento)
                    } else if (primerEvento) {
                        // Se fue al fondo sin que viéramos cuándo llegó: ya estaba al frente antes de `desde`.
                        sumar(e.paquete, desde, e.momento)
                    }
                }
            }
        }

        // Lo que sigue abierto cuenta hasta el final del rango.
        inicioSesion.forEach { (paquete, inicio) -> sumar(paquete, inicio, hasta) }
        return totales
    }
}
