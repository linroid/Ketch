package com.linroid.ketch.app.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Keeps user-facing text in string resources (docs/development/localization.md).
 *
 * Every string literal in `commonMain` that reads like words for people, such as "Paused" or
 * "$count files", counts as hard-coded text, except in log calls, developer errors, deprecation
 * messages, previews and [verbatim] calls. Each file may only have as many as
 * `hardcoded-text-allowlist.txt` says; a count may not rise, and a count that falls must be
 * lowered in the allowlist too. Run
 * `./gradlew :app:shared:jvmTest -PupdateTokenAllowlist` to rewrite it.
 */
class HardcodedTextTest {
  private val sourceRoot = File("src/commonMain/kotlin/com/linroid/ketch/app")
  private val allowlistFile = File("src/jvmTest/resources/hardcoded-text-allowlist.txt")

  @Test fun commonMainSources_matchTheAllowlist() {
    check(sourceRoot.isDirectory) { "Run from app/shared; ${sourceRoot.absolutePath} is missing" }
    val actual = sourceRoot.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .map { it.relativeTo(sourceRoot).invariantSeparatorsPath to it.readText() }
      .filterNot { (path, _) -> path.startsWith("components/preview/") }
      .associate { (path, text) -> path to hardcodedTexts(text).size }
      .filterValues { it > 0 }
    if (System.getProperty("updateTokenAllowlist") == "true") {
      allowlistFile.writeText(
        "# Files that still hold hard-coded user-facing text, with how many literals.\n" +
          actual.toSortedMap().entries.joinToString("") { "${it.key}:${it.value}\n" },
      )
      return
    }
    val allowed = allowlistFile.readLines()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("#") }
      .associate { it.substringBeforeLast(':') to it.substringAfterLast(':').toInt() }
    val problems = (actual.keys + allowed.keys).sorted().mapNotNull { path ->
      val found = actual[path] ?: 0
      val limit = allowed[path] ?: 0
      when {
        found > limit -> "$path: $found hard-coded texts, the allowlist permits $limit: " +
          hardcodedTexts(File(sourceRoot, path).readText()).joinToString { "\"$it\"" }
        found < limit -> "$path: $found hard-coded texts, lower the allowlist from $limit"
        else -> null
      }
    }
    if (problems.isNotEmpty()) {
      fail(
        problems.joinToString("\n") +
          "\nMove the text to composeResources/values, or run ./gradlew :app:shared:jvmTest " +
          "-PupdateTokenAllowlist after removing it.",
      )
    }
  }

  @Test fun hardcodedTexts_findsWordsForPeople() {
    val source = """
      val a = "Paused"
      val b = "${'$'}count files"
      val c = "add.paste"
      val d = "https://example.com/a b"
      val e = verbatim("Firefox")
      // val f = "Commented out"
      /** "In a KDoc" */
      private val log = KetchLogger("AppState")
      fun g() { log.w { "Couldn't start" } }
      fun h() { error("Missing value") }
      val i = "Today ${'$'}{clock("Ignored inner")}"
      val j = ""${'"'}Raw text""${'"'}
      val k = '"'
      val l = "KB"
    """.trimIndent()
    assertEquals(
      listOf("Paused", "\$ files", "Ignored inner", "Today \$", "Raw text"),
      hardcodedTexts(source),
    )
  }
}

/**
 * The constant parts of each string literal in [source] that read like words for people, in
 * order; literals inside template expressions count on their own.
 */
internal fun hardcodedTexts(source: String): List<String> {
  val lines = source.lines()
  return stringLiterals(source)
    .filterNot { literal -> isExempt(lines[literal.line], literal.column) }
    .map { it.text }
    .filter(::readsLikeWords)
}

private fun readsLikeWords(text: String): Boolean {
  if (text.contains("://")) return false
  return Regex("""^[A-Z][a-z]""").containsMatchIn(text.trimStart()) ||
    Regex("""[A-Za-z]{2,}\s+[A-Za-z]|[A-Za-z]\s+[A-Za-z]{2,}|\s[a-z]{2,}""").containsMatchIn(text)
}

/** Whether the literal starting at [column] of [line] is in a call whose text is not for people. */
private fun isExempt(line: String, column: Int): Boolean {
  val before = line.substring(0, column)
  return EXEMPT_CALLS.any { it.containsMatchIn(before) } || before.trimEnd().endsWith("verbatim(")
}

private val EXEMPT_CALLS = listOf(
  Regex("""\blog\.[vdiwe]\b"""),
  Regex("""\bKetchLogger\("""),
  Regex("""\b(error|require|check|requireNotNull|checkNotNull|TODO)\("""),
  Regex("""\b\w+Exception\("""),
  Regex("""\bthrow\b"""),
  Regex("""\bprintln\("""),
  Regex("""@Deprecated\("""),
)

private data class Literal(val text: String, val line: Int, val column: Int)

/** Every string literal of [source] outside comments, with the constant parts of its text. */
private fun stringLiterals(source: String): List<Literal> = LiteralScanner(source).scan()

/** Walks Kotlin source, collecting string literals; template expressions read as [HOLE]. */
private class LiteralScanner(private val source: String) {
  private val literals = mutableListOf<Literal>()
  private var line = 0
  private var lineStart = 0
  private var i = 0

  fun scan(): List<Literal> {
    while (i < source.length) {
      when {
        source.startsWith("//", i) -> advance(indexOrEnd(source.indexOf('\n', i)))
        source.startsWith("/*", i) -> advance(indexOrEnd(source.indexOf("*/", i + 2), 2))
        // A character literal, such as '"' or '\''.
        source[i] == '\'' -> {
          val end = if (source.getOrNull(i + 1) == '\\') i + 4 else i + 3
          advance(minOf(end, source.length))
        }
        source[i] == '"' -> readString()
        else -> advance(i + 1)
      }
    }
    return literals
  }

  private fun indexOrEnd(index: Int, length: Int = 0): Int =
    if (index < 0) source.length else index + length

  private fun advance(to: Int) {
    while (i < to) {
      if (source[i] == '\n') {
        line++
        lineStart = i + 1
      }
      i++
    }
  }

  /** Reads the literal opening at the current position, and the literals in its templates. */
  private fun readString() {
    val raw = source.startsWith("\"\"\"", i)
    val startLine = line
    val startColumn = i - lineStart
    val text = StringBuilder()
    advance(i + if (raw) 3 else 1)
    while (i < source.length) {
      val c = source[i]
      when {
        raw && source.startsWith("\"\"\"", i) -> {
          advance(i + 3)
          break
        }
        !raw && c == '"' -> {
          advance(i + 1)
          break
        }
        !raw && c == '\\' -> {
          text.append(source.getOrNull(i + 1) ?: ' ')
          advance(i + 2)
        }
        c == '$' && source.getOrNull(i + 1) == '{' -> {
          readTemplate()
          text.append(HOLE)
        }
        c == '$' && source.getOrNull(i + 1)?.isLetter() == true -> {
          advance(i + 1)
          while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) {
            advance(i + 1)
          }
          text.append(HOLE)
        }
        else -> {
          text.append(c)
          advance(i + 1)
        }
      }
    }
    literals += Literal(text.toString(), startLine, startColumn)
  }

  private fun readTemplate() {
    advance(i + 2)
    var depth = 1
    while (i < source.length && depth > 0) {
      when (source[i]) {
        '"' -> readString()
        '{' -> {
          depth++
          advance(i + 1)
        }
        '}' -> {
          depth--
          advance(i + 1)
        }
        else -> advance(i + 1)
      }
    }
  }

  companion object {
    /** Stands for a template expression in a literal's text. */
    const val HOLE = '$'
  }
}
