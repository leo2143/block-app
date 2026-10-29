package com.leo.blockapp.adaptadores.entrada.ui.tema

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun TemaBlockApp(content: @Composable () -> Unit) {
    val oscuro = isSystemInDarkTheme()
    val colores = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (oscuro) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        oscuro -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colores, content = content)
}
