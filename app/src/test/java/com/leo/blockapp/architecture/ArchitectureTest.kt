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
        // Generated classes the UI legitimately reads (exact names, not packages).
        "com.leo.blockapp.R",
        "com.leo.blockapp.BuildConfig",
    )

    private val forbiddenUiReferences = listOf(
        "com.leo.blockapp.domain.usecases.SaveBlock",
        "com.leo.blockapp.domain.blocks.BlockValidator",
        "com.leo.blockapp.domain.usage.UsageCalculator",
        "com.leo.blockapp.domain.ports.output.BlockRepository",
        "com.leo.blockapp.domain.ports.output.BlockStateRepository",
        "com.leo.blockapp.domain.rules.HoldPolicy",
        "com.leo.blockapp.domain.rules.BlockEvaluator",
        "com.leo.blockapp.adapters.output.RoomBlockRepository",
        "com.leo.blockapp.domain.future.NewThing",
        "com.leo.blockapp.di.AppModule",
        "com.leo.blockapp.domain.modelx.Foo",
        "com.leo.blockapp.adapters.inputs.Foo",
        "com.leo.blockapp.domain.ports.inputX.Foo",
        "com.leo.blockapp.domain.model_x.Foo",
        "com.leo.blockapp.Repo",
        "com.leo.blockapp.*",
    )

    /**
     * The domain gets "now" from the Clock port, never from the system. Any `<java.time type>.now` is flagged,
     * including `now(clock)`: the domain must use the Clock port instead.
     */
    private val forbiddenDomainClockReads = listOf(
        Regex("""(^|[^\w])System\s*\.\s*currentTimeMillis\b"""),
        Regex("""(^|[^\w])System\s*\.\s*nanoTime\b"""),
        Regex(
            """(^|[^\w])(Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|Year|YearMonth)""" +
                """\s*(\.|::)\s*now\b""",
        ),
        Regex("""(^|[^\w])Clock\s*\.\s*(?i:system)\w*"""),
        Regex("""(^|[^\w])Calendar\s*\.\s*getInstance\s*\("""),
        Regex("""(^|[^\w])GregorianCalendar\b"""),
        Regex("""(^|[^\w])TimeSource\b"""),
        Regex("""(^|[^\w])java\.util\.Date\b"""),
        Regex("""^\s*import\s+java\.util\.\*"""),
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
    fun `domain does not read the system clock`() {
        val violations = findViolations(domainDirectory, forbiddenDomainClockReads)

        assertTrue(
            "The domain must read time through the Clock port:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `clock scanner reports direct system clock reads`() {
        val violations = scanWith(
            "val now = System.currentTimeMillis()\nval fq = java.lang.System.currentTimeMillis()\nval t = System.nanoTime()\n",
            forbiddenDomainClockReads,
        )

        assertEquals(
            listOf(
                "Leaky.kt: val now = System.currentTimeMillis()",
                "Leaky.kt: val fq = java.lang.System.currentTimeMillis()",
                "Leaky.kt: val t = System.nanoTime()",
            ),
            violations,
        )
    }

    @Test
    fun `clock scanner reports java time and legacy clock reads`() {
        val lines = listOf(
            "val a = Instant.now()",
            "val b = LocalDate.now()",
            "val c = LocalDateTime.now()",
            "val d = ZonedDateTime.now()",
            "val e = Clock.systemUTC()",
            "val f = Calendar.getInstance()",
            "import java.util.Date",
            "val g = Clock.System.now()",
            "val h = LocalTime.now()",
            "val i = OffsetDateTime.now()",
            "val j = Year.now()",
            "val k = YearMonth.now()",
            "val l = Instant::now",
            "val m = ZonedDateTime.now(clock)",
            "val n = GregorianCalendar()",
            "val o = TimeSource.Monotonic.markNow()",
            "import java.util.*",
        )

        assertEquals(lines.map { "Leaky.kt: $it" }, scanWith(lines.joinToString("\n") + "\n", forbiddenDomainClockReads))
    }

    @Test
    fun `clock scanner ignores the Clock port and comments`() {
        val violations = scanWith(
            "// System.currentTimeMillis() is forbidden\nval now = clock.nowMillis()\nval s = MySystem.currentTimeMillisX()\n",
            forbiddenDomainClockReads,
        )

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `domain scanner reports android and dagger imports`() {
        val violations = scanWith(
            "import android.content.Context\nimport androidx.compose.runtime.Composable\n" +
                "import dagger.Module\nimport javax.inject.Inject\nimport kotlin.math.max\n",
            forbiddenDomainPackages.map(::importOf),
        )

        assertEquals(
            listOf(
                "Leaky.kt: import android.content.Context",
                "Leaky.kt: import androidx.compose.runtime.Composable",
                "Leaky.kt: import dagger.Module",
                "Leaky.kt: import javax.inject.Inject",
            ),
            violations,
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
                "import com.leo.blockapp.R\n" +
                "import com.leo.blockapp.BuildConfig\n" +
                "val own = com.leo.blockapp.domain.model.AppUsage()\n",
        )

        assertEquals(emptyList<String>(), violations)
    }

    private fun scanUi(source: String): List<String> {
        val scratchDirectory = createTempDirectory(prefix = "architecture-scan").toFile()
        try {
            File(scratchDirectory, "LeakyScreen.kt").writeText(source)
            return findViolations(scratchDirectory, inputAdapterRules)
        } finally {
            scratchDirectory.deleteRecursively()
        }
    }

    private fun scan(source: String): List<String> = scanWith(source, listOf(anyUseOf("kotlinx.serialization")))

    private fun scanWith(source: String, forbidden: List<Regex>): List<String> {
        val scratchDirectory = createTempDirectory(prefix = "architecture-scan").toFile()
        try {
            File(scratchDirectory, "Leaky.kt").writeText(source)
            return findViolations(scratchDirectory, forbidden)
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
