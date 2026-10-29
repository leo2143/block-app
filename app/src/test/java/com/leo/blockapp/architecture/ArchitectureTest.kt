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

    private val allowedInputAdapterPackages = listOf(
        "com.leo.blockapp.domain.model",
        "com.leo.blockapp.domain.ports.input",
        "com.leo.blockapp.adapters.input",
    )

    /** Any `com.leo.blockapp.` reference (import or fully qualified) that is not in the allowlist. */
    private val inputAdapterRules = listOf(
        Regex(
            """(^|[^\w.])com\.leo\.blockapp\.(?!(${allowedInputAdapterPackages.joinToString("|") { Regex.escape(it.removePrefix("com.leo.blockapp.")) }})\b)""",
        ),
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
        val violations = findViolations(inputAdaptersDirectory, inputAdapterRules)

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
    fun `ui scanner reports a forbidden output port import in a ui file`() {
        val violations = scanUi(
            "package leaky\n\nimport com.leo.blockapp.domain.ports.output.BlockRepository\n" +
                "import com.leo.blockapp.domain.ports.input.SaveBlock\n",
        )

        assertEquals(
            listOf("LeakyScreen.kt: import com.leo.blockapp.domain.ports.output.BlockRepository"),
            violations,
        )
    }

    @Test
    fun `ui scanner rejects imports outside the allowed packages`() {
        forbiddenUiReferences.forEach { reference ->
            val line = "import $reference"

            assertEquals(
                "Expected a violation for: $line",
                listOf("LeakyScreen.kt: $line"),
                scanUi("package leaky\n\n$line\n"),
            )
        }
    }

    @Test
    fun `ui scanner rejects fully qualified uses outside the allowed packages`() {
        forbiddenUiReferences.forEach { reference ->
            val line = "val leak = $reference()"

            assertEquals(
                "Expected a violation for: $line",
                listOf("LeakyScreen.kt: $line"),
                scanUi("package leaky\n\n$line\n"),
            )
        }
    }

    @Test
    fun `ui scanner accepts the allowed packages`() {
        val violations = scanUi(
            "package com.leo.blockapp.adapters.input.ui.blocks\n\n" +
                "import com.leo.blockapp.domain.model.BlockViolation\n" +
                "import com.leo.blockapp.domain.ports.input.SaveBlock\n" +
                "import com.leo.blockapp.adapters.input.ui.theme.BlockAppTheme\n" +
                "val own = com.leo.blockapp.domain.model.AppUsage()\n",
        )

        assertEquals(emptyList<String>(), violations)
    }

    private val forbiddenUiReferences = listOf(
        "com.leo.blockapp.domain.usecases.SaveBlock",
        "com.leo.blockapp.domain.blocks.BlockValidator",
        "com.leo.blockapp.domain.usage.UsageCalculator",
        "com.leo.blockapp.domain.ports.output.BlockRepository",
        "com.leo.blockapp.adapters.output.RoomBlockRepository",
        "com.leo.blockapp.domain.future.NewThing",
        "com.leo.blockapp.di.AppModule",
    )

    private fun scanUi(source: String): List<String> {
        val scratchDirectory = createTempDirectory(prefix = "architecture-scan").toFile()
        try {
            File(scratchDirectory, "LeakyScreen.kt").writeText(source)
            return findViolations(scratchDirectory, inputAdapterRules)
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
