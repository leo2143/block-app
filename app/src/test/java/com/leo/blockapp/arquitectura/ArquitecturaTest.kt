package com.leo.blockapp.arquitectura

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hace cumplir la regla 1 de la arquitectura: el dominio no conoce Android ni Hilt. */
class ArquitecturaTest {

    private val prohibidos = listOf("import android.", "import androidx.", "import dagger.", "import javax.inject.")

    @Test
    fun `el dominio no importa Android ni Hilt`() {
        val dominio = File("src/main/java/com/leo/blockapp/dominio")
        assertTrue("No se encontró la carpeta del dominio: ${dominio.absolutePath}", dominio.isDirectory)

        val violaciones = dominio.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { archivo ->
                archivo.readLines()
                    .filter { linea -> prohibidos.any { linea.trim().startsWith(it) } }
                    .map { "${archivo.name}: ${it.trim()}" }
            }
            .toList()

        assertTrue("El dominio debe ser Kotlin puro:\n" + violaciones.joinToString("\n"), violaciones.isEmpty())
    }
}
