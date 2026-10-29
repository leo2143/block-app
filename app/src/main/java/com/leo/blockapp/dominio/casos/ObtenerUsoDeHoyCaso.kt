package com.leo.blockapp.dominio.casos

import com.leo.blockapp.dominio.modelo.ResultadoUsoHoy
import com.leo.blockapp.dominio.modelo.UsoDeApp
import com.leo.blockapp.dominio.puertos.entrada.ObtenerUsoDeHoy
import com.leo.blockapp.dominio.puertos.salida.CatalogoApps
import com.leo.blockapp.dominio.puertos.salida.FuenteEventosUso
import com.leo.blockapp.dominio.puertos.salida.PermisosSistema
import com.leo.blockapp.dominio.puertos.salida.Reloj
import com.leo.blockapp.dominio.uso.CalculadoraUso

/** Implementa el puerto de entrada usando solo puertos de salida: no sabe nada de Android. */
class ObtenerUsoDeHoyCaso(
    private val fuenteEventos: FuenteEventosUso,
    private val catalogo: CatalogoApps,
    private val permisos: PermisosSistema,
    private val reloj: Reloj,
) : ObtenerUsoDeHoy {

    override suspend fun invoke(): ResultadoUsoHoy {
        if (!permisos.tieneAccesoDeUso()) return ResultadoUsoHoy.SinPermiso

        val ahora = reloj.ahora()
        val desde = reloj.inicioDelDia(ahora)
        val tiempos = CalculadoraUso.tiempoEnPrimerPlano(fuenteEventos.eventos(desde, ahora), desde, ahora)

        val apps = catalogo.appsInstaladas()
            .map { UsoDeApp(it, tiempos[it.paquete] ?: 0L) }
            .sortedWith(compareByDescending<UsoDeApp> { it.msHoy }.thenBy { it.app.nombre.lowercase() })

        return ResultadoUsoHoy.Listo(apps)
    }
}
