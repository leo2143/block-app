package com.leo.blockapp.dominio.uso

import com.leo.blockapp.dominio.modelo.EventoUso
import com.leo.blockapp.dominio.modelo.TipoEvento.AL_FONDO
import com.leo.blockapp.dominio.modelo.TipoEvento.AL_FRENTE
import org.junit.Assert.assertEquals
import org.junit.Test

class CalculadoraUsoTest {

    private val min = 60_000L
    private val ig = "com.instagram.android"
    private val x = "com.twitter.android"

    private fun frente(paquete: String, momento: Long, actividad: String = "Main") =
        EventoUso(paquete, actividad, AL_FRENTE, momento)

    private fun fondo(paquete: String, momento: Long, actividad: String = "Main") =
        EventoUso(paquete, actividad, AL_FONDO, momento)

    private fun calcular(vararg eventos: EventoUso, desde: Long = 0L, hasta: Long = 100 * min) =
        CalculadoraUso.tiempoEnPrimerPlano(eventos.toList(), desde, hasta)

    @Test
    fun `una sesion simple`() {
        assertEquals(mapOf(ig to 5 * min), calcular(frente(ig, 10 * min), fondo(ig, 15 * min)))
    }

    @Test
    fun `varias sesiones de la misma app se suman`() {
        val uso = calcular(
            frente(ig, 10 * min), fondo(ig, 15 * min),
            frente(ig, 30 * min), fondo(ig, 33 * min),
        )
        assertEquals(8 * min, uso[ig])
    }

    @Test
    fun `una app que sigue abierta cuenta hasta el final del rango`() {
        assertEquals(10 * min, calcular(frente(ig, 90 * min))[ig])
    }

    @Test
    fun `una app que ya estaba abierta antes del rango cuenta desde el inicio`() {
        val uso = calcular(fondo(ig, 25 * min), desde = 20 * min)
        assertEquals(5 * min, uso[ig])
    }

    @Test
    fun `cambiar de pantalla dentro de la misma app no corta ni duplica el tiempo`() {
        val uso = calcular(
            frente(ig, 10 * min, "Feed"),
            frente(ig, 12 * min, "Reels"),
            fondo(ig, 12 * min, "Feed"),
            fondo(ig, 20 * min, "Reels"),
        )
        assertEquals(10 * min, uso[ig])
    }

    @Test
    fun `cada app se cuenta por separado`() {
        val uso = calcular(
            frente(ig, 10 * min), fondo(ig, 15 * min),
            frente(x, 15 * min), fondo(x, 18 * min),
        )
        assertEquals(mapOf(ig to 5 * min, x to 3 * min), uso)
    }

    @Test
    fun `los eventos desordenados se ordenan por momento`() {
        assertEquals(5 * min, calcular(fondo(ig, 15 * min), frente(ig, 10 * min))[ig])
    }

    @Test
    fun `los eventos fuera del rango se ignoran`() {
        val uso = calcular(
            frente(ig, 1 * min), fondo(ig, 2 * min),
            frente(ig, 10 * min), fondo(ig, 15 * min),
            desde = 5 * min,
        )
        assertEquals(5 * min, uso[ig])
    }

    @Test
    fun `sin eventos no hay uso`() {
        assertEquals(emptyMap<String, Long>(), calcular())
    }
}
