package com.leo.blockapp.dominio.casos

import com.leo.blockapp.dominio.modelo.AppInstalada
import com.leo.blockapp.dominio.modelo.EventoUso
import com.leo.blockapp.dominio.modelo.ResultadoUsoHoy
import com.leo.blockapp.dominio.modelo.TipoEvento
import com.leo.blockapp.dominio.modelo.UsoDeApp
import com.leo.blockapp.dominio.puertos.salida.CatalogoApps
import com.leo.blockapp.dominio.puertos.salida.FuenteEventosUso
import com.leo.blockapp.dominio.puertos.salida.PermisosSistema
import com.leo.blockapp.dominio.puertos.salida.Reloj
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Los adaptadores reales se reemplazan por versiones falsas: el caso de uso se prueba sin teléfono. */
class ObtenerUsoDeHoyCasoTest {

    private val min = 60_000L
    private val medianoche = 1_000 * min
    private val instagram = AppInstalada("com.instagram.android", "Instagram")
    private val x = AppInstalada("com.twitter.android", "X")
    private val whatsapp = AppInstalada("com.whatsapp", "WhatsApp")

    private class FuenteFalsa(private val eventos: List<EventoUso>) : FuenteEventosUso {
        var rangoPedido: Pair<Long, Long>? = null
        override suspend fun eventos(desde: Long, hasta: Long): List<EventoUso> {
            rangoPedido = desde to hasta
            return eventos
        }
    }

    private class CatalogoFalso(private val apps: List<AppInstalada>) : CatalogoApps {
        override suspend fun appsInstaladas() = apps
    }

    private class PermisosFalsos(private val concedido: Boolean) : PermisosSistema {
        override fun tieneAccesoDeUso() = concedido
    }

    private inner class RelojFalso(private val ahora: Long) : Reloj {
        override fun ahora() = ahora
        override fun inicioDelDia(momento: Long) = medianoche
    }

    private fun evento(app: AppInstalada, tipo: TipoEvento, minuto: Long) =
        EventoUso(app.paquete, "Main", tipo, medianoche + minuto * min)

    @Test
    fun `sin permiso no consulta el uso`() = runTest {
        val fuente = FuenteFalsa(emptyList())
        val caso = ObtenerUsoDeHoyCaso(fuente, CatalogoFalso(listOf(instagram)), PermisosFalsos(false), RelojFalso(medianoche))

        assertEquals(ResultadoUsoHoy.SinPermiso, caso())
        assertNull(fuente.rangoPedido)
    }

    @Test
    fun `consulta desde la medianoche hasta ahora`() = runTest {
        val ahora = medianoche + 90 * min
        val fuente = FuenteFalsa(emptyList())
        val caso = ObtenerUsoDeHoyCaso(fuente, CatalogoFalso(emptyList()), PermisosFalsos(true), RelojFalso(ahora))

        caso()

        assertEquals(medianoche to ahora, fuente.rangoPedido)
    }

    @Test
    fun `ordena por uso y muestra en cero las apps no usadas`() = runTest {
        val fuente = FuenteFalsa(
            listOf(
                evento(x, TipoEvento.AL_FRENTE, 10), evento(x, TipoEvento.AL_FONDO, 13),
                evento(instagram, TipoEvento.AL_FRENTE, 20), evento(instagram, TipoEvento.AL_FONDO, 30),
            ),
        )
        val caso = ObtenerUsoDeHoyCaso(
            fuente,
            CatalogoFalso(listOf(whatsapp, x, instagram)),
            PermisosFalsos(true),
            RelojFalso(medianoche + 60 * min),
        )

        val esperado = ResultadoUsoHoy.Listo(
            listOf(
                UsoDeApp(instagram, 10 * min),
                UsoDeApp(x, 3 * min),
                UsoDeApp(whatsapp, 0L),
            ),
        )
        assertEquals(esperado, caso())
    }
}
