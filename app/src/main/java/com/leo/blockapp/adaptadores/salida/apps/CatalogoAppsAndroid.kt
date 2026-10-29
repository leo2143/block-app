package com.leo.blockapp.adaptadores.salida.apps

import android.content.Context
import android.content.Intent
import com.leo.blockapp.dominio.modelo.AppInstalada
import com.leo.blockapp.dominio.puertos.salida.CatalogoApps
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Adaptador de salida: lista las apps que aparecen en el launcher (las que el usuario puede abrir). */
class CatalogoAppsAndroid @Inject constructor(
    @ApplicationContext private val context: Context,
) : CatalogoApps {

    override suspend fun appsInstaladas(): List<AppInstalada> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { AppInstalada(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
    }
}
