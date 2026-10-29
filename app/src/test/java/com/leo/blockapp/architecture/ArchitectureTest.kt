package com.leo.blockapp.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Enforces the architecture rules: the domain is pure Kotlin and the UI only talks to input ports. */
class ArchitectureTest {

    private val domainDirectory = File("src/main/java/com/leo/blockapp/domain")
    private val inputAdaptersDirectory = File("src/main/java/com/leo/blockapp/adapters/input")

    private val forbiddenDomainPackages = listOf("android", "androidx", "dagger", "javax.inject")

    private val forbiddenInputAdapterPackages = listOf(
        "com.leo.blockapp.adapters.output",
        "com.leo.blockapp.domain.ports.output",
        "com.leo.blockapp.domain.usecases",
        "com.leo.blockapp.domain.blocks",
    )

    @Test
    fun `domain does not import Android or Hilt`() {
        val violations = findViolations(domainDirectory, forbiddenDomainPackages.map(::importOf))

        assertTrue("The domain must be pure Kotlin:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun `domain does not depend on kotlinx serialization`() {
        val violations = findViolations(domainDirectory, listOf(anyUseOf("kotlinx.serialization")))

        assertTrue(
            "The domain must not depend on serialization (storage adapters own it):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `input adapters only depend on input ports and models`() {
        val violations = findViolations(inputAdaptersDirectory, forbiddenInputAdapterPackages.map(::importOf))

        assertTrue(
            "The UI must only use domain.model and domain.ports.input:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `scanner reports a forbidden import`() {
        val violations = scan("package leaky\n\nimport kotlinx.serialization.Serializable\n\nclass Leaky\n")

        assertEquals(listOf("Leaky.kt: import kotlinx.serialization.Serializable"), violations)
    }

    @Test
    fun `scanner reports an import with irregular whitespace`() {
        val violations = scan("package leaky\n\n   import   kotlinx.serialization.json.Json\n")

        assertEquals(listOf("Leaky.kt: import   kotlinx.serialization.json.Json"), violations)
    }

    @Test
    fun `scanner reports a fully qualified usage without import`() {
        val violations = scan("package leaky\n\nval json = kotlinx.serialization.json.Json { }\n")

        assertEquals(listOf("Leaky.kt: val json = kotlinx.serialization.json.Json { }"), violations)
    }

    @Test
    fun `scanner ignores commented mentions and look-alike packages`() {
        val violations = scan(
            "package leaky\n\n// kotlinx.serialization.Json is not used here\n" +
                "/** See kotlinx.serialization.Serializable */\n" +
                " * kotlinx.serialization.Foo\n" +
                "import mykotlinx.serialization.Fake\n",
        )

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `scanner reports a forbidden output port import in a ui file`() {
        val scratchDirectory = createTempDirectory(prefix = "architecture-scan").toFile()
        try {
            File(scratchDirectory, "LeakyScreen.kt").writeText(
                "package leaky\n\nimport com.leo.blockapp.domain.ports.output.BlockRepository\n" +
                    "import com.leo.blockapp.domain.ports.input.SaveBlock\n",
            )

            val violations = findViolations(scratchDirectory, forbiddenInputAdapterPackages.map(::importOf))

            assertEquals(
                listOf("LeakyScreen.kt: import com.leo.blockapp.domain.ports.output.BlockRepository"),
                violations,
            )
        } finally {
            scratchDirectory.deleteRecursively()
        }
    }

    private fun scan(source: String): List<String> {
        val scratchDirectory = createTempDirectory(prefix = "architecture-scan").toFile()
        try {
            File(scratchDirectory, "Leaky.kt").writeText(source)
            return findViolations(scratchDirectory, listOf(anyUseOf("kotlinx.serialization")))
        } finally {
            scratchDirectory.deleteRecursively()
        }
    }

    /** Matches `import <pkg>` at the start of a line, tolerating leading and inner whitespace. */
    private fun importOf(packageName: String): Regex =
        Regex("""^\s*import\s+${Regex.escape(packageName)}\b""")

    /** Matches an import of [packageName] or any fully qualified reference to it, outside comments. */
    private fun anyUseOf(packageName: String): Regex =
        Regex("""(^|[^\w.])${Regex.escape(packageName)}\.""")

    private fun findViolations(directory: File, forbidden: List<Regex>): List<String> {
        assertTrue("Directory not found: ${directory.absolutePath}", directory.isDirectory)

        return directory.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { sourceFile ->
                sourceFile.readLines()
                    .filterNot { isCommentLine(it) }
                    .filter { line -> forbidden.any { it.containsMatchIn(line) } }
                    .map { "${sourceFile.name}: ${it.trim()}" }
            }
            .toList()
    }

    private fun isCommentLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
    }
}
