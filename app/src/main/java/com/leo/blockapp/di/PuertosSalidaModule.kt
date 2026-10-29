package com.leo.blockapp.di

import com.leo.blockapp.adaptadores.salida.apps.CatalogoAppsAndroid
import com.leo.blockapp.adaptadores.salida.permisos.PermisosAndroid
import com.leo.blockapp.adaptadores.salida.reloj.RelojSistema
import com.leo.blockapp.adaptadores.salida.uso.FuenteEventosUsageStats
import com.leo.blockapp.dominio.puertos.salida.CatalogoApps
import com.leo.blockapp.dominio.puertos.salida.FuenteEventosUso
import com.leo.blockapp.dominio.puertos.salida.PermisosSistema
import com.leo.blockapp.dominio.puertos.salida.Reloj
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Conecta cada puerto de salida con el adaptador de Android que lo implementa. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PuertosSalidaModule {

    @Binds
    abstract fun fuenteEventosUso(impl: FuenteEventosUsageStats): FuenteEventosUso

    @Binds
    abstract fun catalogoApps(impl: CatalogoAppsAndroid): CatalogoApps

    @Binds
    abstract fun permisosSistema(impl: PermisosAndroid): PermisosSistema

    @Binds
    abstract fun reloj(impl: RelojSistema): Reloj
}
