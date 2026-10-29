package com.leo.blockapp.adaptadores.salida.permisos

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import com.leo.blockapp.dominio.puertos.salida.PermisosSistema
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PermisosAndroid @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermisosSistema {

    override fun tieneAccesoDeUso(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val modo = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return modo == AppOpsManager.MODE_ALLOWED
    }
}
