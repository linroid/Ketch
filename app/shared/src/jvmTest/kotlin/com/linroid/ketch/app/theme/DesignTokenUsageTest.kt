package com.linroid.ketch.app.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps feature code on the design tokens (docs/design/ux-redesign.md §3.14).
 *
 * Files under `theme/` and `components/` define the tokens; everywhere else in `commonMain`,
 * literal radii, colors and text sizes, `MaterialTheme.` and Material icons may only appear as
 * often as `design-token-allowlist.txt` says. The counts may not rise, and a count that falls
 * must be lowered in the allowlist too, so the list only shrinks. Run
 * `./gradlew :app:shared:jvmTest -PupdateTokenAllowlist` to rewrite it.
 */
class DesignTokenUsageTest {
  private val sourceRoot = File("src/commonMain/kotlin/com/linroid/ketch/app")
  private val allowlistFile = File("src/jvmTest/resources/design-token-allowlist.txt")

  @Test fun commonMainSources_outsideTheTokens_matchTheAllowlist() {
    val actual = scan(sources())
    if (System.getProperty("updateTokenAllowlist") == "true") {
      allowlistFile.writeText(formatAllowlist(actual))
      return
    }
    val problems = compareToAllowlist(actual, parseAllowlist(allowlistFile.readText()))
    if (problems.isNotEmpty()) {
      fail(
        problems.joinToString("\n") +
          "\nUse the theme tokens, or run ./gradlew :app:shared:jvmTest -PupdateTokenAllowlist" +
          " after removing offenders.",
      )
    }
  }

  @Test fun brandEmber_outsideTheBrandVisuals_isNeverUsed() {
    val offenders = sources().filter { (path, text) ->
      path !in BRAND_EMBER_FILES && text.contains("brandEmber")
    }
    assertTrue(offenders.isEmpty(), "brandEmber used in ${offenders.keys}")
  }

  @Test fun countOffenders_literalsAndMaterial_countsEachPattern() {
    val source = """
      import androidx.compose.material.icons.Icons
      val shape = RoundedCornerShape(12.dp)
      val pill = RoundedCornerShape(percent = 50)
      val ink = Color(0xFF141A26)
      val size = 14.sp
      val tracking = (-0.3).sp
      val spacing = 0.6.sp
      val style = MaterialTheme.typography.bodyLarge
      val token = KetchTheme.typography.body
    """.trimIndent()
    assertEquals(
      mapOf(
        "RoundedCornerShape(<number>.dp" to 1,
        "Color(0x" to 1,
        "<number>.sp" to 3,
        "MaterialTheme." to 1,
        "androidx.compose.material.icons" to 1,
      ),
      countOffenders(source),
    )
  }

  @Test fun compareToAllowlist_countRises_reportsIt() {
    val problems = compareToAllowlist(
      actual = mapOf(Offense("ui/A.kt", "Color(0x") to 3),
      allowed = mapOf(Offense("ui/A.kt", "Color(0x") to 2),
    )
    assertEquals(1, problems.size)
    assertTrue(problems.single().contains("3"))
  }

  @Test fun compareToAllowlist_countFallsOrDisappears_reportsIt() {
    val problems = compareToAllowlist(
      actual = mapOf(Offense("ui/A.kt", "Color(0x") to 1),
      allowed = mapOf(
        Offense("ui/A.kt", "Color(0x") to 2,
        Offense("ui/B.kt", "MaterialTheme.") to 4,
      ),
    )
    assertEquals(2, problems.size)
  }

  @Test fun compareToAllowlist_sameCounts_reportsNothing() {
    val counts = mapOf(Offense("ui/A.kt", "<number>.sp") to 2)
    assertEquals(emptyList(), compareToAllowlist(counts, counts))
  }

  @Test fun parseAllowlist_formattedCounts_roundTrip() {
    val counts = mapOf(
      Offense("ui/B.kt", "MaterialTheme.") to 4,
      Offense("ui/A.kt", "RoundedCornerShape(<number>.dp") to 1,
    )
    val text = formatAllowlist(counts)
    assertTrue(text.lines().first().startsWith("ui/A.kt:"))
    assertEquals(counts, parseAllowlist(text))
  }

  /** Source text of every `commonMain` file outside `theme/` and `components/`, by path. */
  private fun sources(): Map<String, String> {
    check(sourceRoot.isDirectory) { "Run from app/shared; ${sourceRoot.absolutePath} is missing" }
    return sourceRoot.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .associate { it.relativeTo(sourceRoot).invariantSeparatorsPath to it.readText() }
      .filterKeys { !it.startsWith("theme/") }
  }

  private fun scan(sources: Map<String, String>): Map<Offense, Int> = buildMap {
    for ((path, text) in sources) {
      if (path.startsWith("components/")) continue
      for ((pattern, count) in countOffenders(text)) put(Offense(path, pattern), count)
    }
  }
}

/** A guarded [pattern] found in the file at [path], relative to the app package. */
internal data class Offense(val path: String, val pattern: String)

private val BRAND_EMBER_FILES = setOf(
  "components/KetchLogoTile.kt",
  "components/SailLanesIllustration.kt",
  "components/LaneStrip.kt",
  // The Add button's hover and drop fill, the one control the gradient may light.
  "components/KetchAddButton.kt",
)

private val PATTERNS = linkedMapOf(
  "RoundedCornerShape(<number>.dp" to Regex("""RoundedCornerShape\(\s*\d+(\.\d+)?\.dp"""),
  "Color(0x" to Regex("""Color\(0x"""),
  "<number>.sp" to Regex("""(?<![\w.])\(?-?\d+(\.\d+)?\)?\.sp\b"""),
  "MaterialTheme." to Regex("""\bMaterialTheme\."""),
  "androidx.compose.material.icons" to Regex("""androidx\.compose\.material\.icons"""),
)

/** How often each guarded pattern occurs in [source]; patterns that do not occur are left out. */
internal fun countOffenders(source: String): Map<String, Int> = buildMap {
  for ((name, regex) in PATTERNS) {
    val count = regex.findAll(source).count()
    if (count > 0) put(name, count)
  }
}

/** Differences between the [actual] counts and the [allowed] ones, one line each. */
internal fun compareToAllowlist(
  actual: Map<Offense, Int>,
  allowed: Map<Offense, Int>,
): List<String> {
  return (actual.keys + allowed.keys).sortedBy { it.line }.mapNotNull { offense ->
    val found = actual[offense] ?: 0
    val limit = allowed[offense] ?: 0
    when {
      found > limit -> "${offense.line}: $found uses, the allowlist permits $limit"
      found < limit -> "${offense.line}: $found uses, lower the allowlist from $limit"
      else -> null
    }
  }
}

/** Parses `path:pattern:count` lines; blank lines and `#` comments are skipped. */
internal fun parseAllowlist(text: String): Map<Offense, Int> {
  return text.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .associate { line ->
      val path = line.substringBefore(':')
      val pattern = line.substringAfter(':').substringBeforeLast(':')
      val count = line.substringAfterLast(':').toInt()
      Offense(path, pattern) to count
    }
}

/** Writes [counts] as sorted `path:pattern:count` lines. */
internal fun formatAllowlist(counts: Map<Offense, Int>): String {
  return counts.entries
    .filter { it.value > 0 }
    .sortedBy { it.key.line }
    .joinToString(separator = "") { "${it.key.line}:${it.value}\n" }
}

private val Offense.line: String get() = "$path:$pattern"
