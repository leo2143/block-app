package com.leo.blockapp.di

import com.leo.blockapp.dominio.casos.ObtenerUsoDeHoyCaso
import com.leo.blockapp.dominio.puertos.entrada.ObtenerUsoDeHoy
import com.leo.blockapp.dominio.puertos.salida.CatalogoApps
import com.leo.blockapp.dominio.puertos.salida.FuenteEventosUso
import com.leo.blockapp.dominio.puertos.salida.PermisosSistema
import com.leo.blockapp.dominio.puertos.salida.Reloj
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Conecta cada puerto de entrada con su caso de uso. Se usa @Provides (y no @Inject en el caso)
 * para que el dominio no dependa de ninguna anotación de Hilt.
 */
@Module
@InstallIn(SingletonComponent::class)
object CasosModule {

    @Provides
    fun obtenerUsoDeHoy(
        fuenteEventos: FuenteEventosUso,
        catalogo: CatalogoApps,
        permisos: PermisosSistema,
        reloj: Reloj,
    ): ObtenerUsoDeHoy = ObtenerUsoDeHoyCaso(fuenteEventos, catalogo, permisos, reloj)
}
