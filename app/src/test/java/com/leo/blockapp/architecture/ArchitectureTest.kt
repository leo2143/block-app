package com.leo.blockapp.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Enforces architecture rule 1: the domain knows nothing about Android or Hilt. */
class ArchitectureTest {

    private val forbiddenImportPrefixes =
        listOf("import android.", "import androidx.", "import dagger.", "import javax.inject.")

    @Test
    fun `domain does not import Android or Hilt`() {
        val domainDirectory = File("src/main/java/com/leo/blockapp/domain")
        assertTrue("Domain directory not found: ${domainDirectory.absolutePath}", domainDirectory.isDirectory)

        val forbiddenImports = domainDirectory.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { sourceFile ->
                sourceFile.readLines()
                    .filter { line -> forbiddenImportPrefixes.any { line.trim().startsWith(it) } }
                    .map { "${sourceFile.name}: ${it.trim()}" }
            }
            .toList()

        assertTrue("The domain must be pure Kotlin:\n" + forbiddenImports.joinToString("\n"), forbiddenImports.isEmpty())
    }
}
