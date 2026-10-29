package com.leo.blockapp.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Enforces architecture rule 1: the domain knows nothing about Android or Hilt. */
class ArchitectureTest {

    private val domainDirectory = File("src/main/java/com/leo/blockapp/domain")

    private val forbiddenDomainImportPrefixes =
        listOf("import android.", "import androidx.", "import dagger.", "import javax.inject.")

    @Test
    fun `domain does not import Android or Hilt`() {
        val forbiddenImports = findForbiddenImports(domainDirectory, forbiddenDomainImportPrefixes)

        assertTrue("The domain must be pure Kotlin:\n" + forbiddenImports.joinToString("\n"), forbiddenImports.isEmpty())
    }

    @Test
    fun `domain does not import kotlinx serialization`() {
        val forbiddenImports = findForbiddenImports(domainDirectory, listOf("import kotlinx.serialization."))

        assertTrue(
            "The domain must not depend on serialization (storage adapters own it):\n" +
                forbiddenImports.joinToString("\n"),
            forbiddenImports.isEmpty(),
        )
    }

    @Test
    fun `import scanner reports a forbidden import`() {
        val scratchDirectory = createTempDir(prefix = "architecture-scan")
        try {
            File(scratchDirectory, "Leaky.kt").writeText(
                "package leaky\n\nimport kotlinx.serialization.Serializable\n\nclass Leaky\n",
            )

            val forbiddenImports = findForbiddenImports(scratchDirectory, listOf("import kotlinx.serialization."))

            assertEquals(listOf("Leaky.kt: import kotlinx.serialization.Serializable"), forbiddenImports)
        } finally {
            scratchDirectory.deleteRecursively()
        }
    }

    private fun findForbiddenImports(directory: File, forbiddenPrefixes: List<String>): List<String> {
        assertTrue("Directory not found: ${directory.absolutePath}", directory.isDirectory)

        return directory.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { sourceFile ->
                sourceFile.readLines()
                    .filter { line -> forbiddenPrefixes.any { line.trim().startsWith(it) } }
                    .map { "${sourceFile.name}: ${it.trim()}" }
            }
            .toList()
    }
}
