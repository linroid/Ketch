package com.linroid.ketch.app.i18n

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks the string resources of the apps (docs/development/localization.md): English is
 * well formed, and every translation only has keys English has, with the same placeholders.
 */
class LocalizationResourcesTest {
  private val roots = listOf(
    File("src/commonMain/composeResources"),
    File("../desktop/src/main/composeResources"),
  ).filter { it.isDirectory }

  @Test fun english_placeholdersAndEscapes_areWellFormed() {
    val problems = roots.flatMap { root ->
      source(root).flatMap { (name, entry) -> formProblems("$root/values", name, entry) }
    }
    assertTrue(problems.isEmpty(), problems.joinToString("\n"))
  }

  @Test fun translations_matchEnglish() {
    val problems = mutableListOf<String>()
    for (root in roots) {
      val english = source(root)
      for (dir in translationDirs(root)) {
        val translated = strings(dir)
        for ((name, entry) in translated) {
          val where = "${dir.name}/$name"
          val original = english[name]
          if (original == null) {
            problems += "$where is not in English"
            continue
          }
          if (original.plural != entry.plural) {
            problems += "$where is a ${entry.kind}, English has a ${original.kind}"
            continue
          }
          problems += formProblems(dir.path, name, entry)
          problems += placeholderProblems(where, original, entry)
        }
      }
    }
    if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
  }

  @Test fun translations_reportMissingStrings() {
    for (root in roots) {
      val english = source(root).keys
      for (dir in translationDirs(root)) {
        val missing = english - strings(dir).keys
        if (missing.isNotEmpty()) {
          println("${dir.path}: ${missing.size} strings show in English: ${missing.sorted()}")
        }
      }
    }
  }

  @Test fun placeholders_countsEachArgument() {
    assertEquals(setOf(1, 2), placeholders("%2\$d of %1\$s"))
    assertEquals(emptySet(), placeholders("100%"))
  }

  @Test fun formProblems_androidEscapesAndBarePlaceholders_areReported() {
    val entry = Entry(plural = false, forms = mapOf("" to "Couldn\\'t open %s"))
    assertEquals(2, formProblems("values", "x", entry).size)
  }

  private fun source(root: File): Map<String, Entry> = strings(File(root, "values"))

  private fun translationDirs(root: File): List<File> =
    root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values-") }.sorted()

  /** Every string and plural of the XML files in [dir], by name. */
  private fun strings(dir: File): Map<String, Entry> = buildMap {
    for (file in dir.listFiles().orEmpty().filter { it.extension == "xml" }) {
      val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
      val nodes = document.documentElement.childNodes
      for (i in 0 until nodes.length) {
        val node = nodes.item(i) as? Element ?: continue
        val name = node.getAttribute("name")
        check(name !in this) { "${file.path}: $name is defined twice" }
        when (node.tagName) {
          "string" -> put(name, Entry(plural = false, forms = mapOf("" to node.textContent)))
          "plurals" -> {
            val items = node.getElementsByTagName("item")
            val forms = (0 until items.length).associate {
              val item = items.item(it) as Element
              item.getAttribute("quantity") to item.textContent
            }
            put(name, Entry(plural = true, forms = forms))
          }
          else -> error("${file.path}: unexpected <${node.tagName}>")
        }
      }
    }
  }

  private fun formProblems(dir: String, name: String, entry: Entry): List<String> = buildList {
    if (entry.plural && "other" !in entry.forms) add("$dir/$name has no \"other\" form")
    for ((quantity, text) in entry.forms) {
      val where = if (quantity.isEmpty()) "$dir/$name" else "$dir/$name[$quantity]"
      if ("\\'" in text || "\\\"" in text) add("$where escapes a quote, which Compose shows as is")
      val bare = BARE_PLACEHOLDER.findAll(text).map { it.value }.toList()
      if (bare.isNotEmpty()) add("$where has placeholders without a position: $bare")
      if (text != text.trim() || '\n' in text) add("$where starts or ends with whitespace")
    }
  }

  private fun placeholderProblems(where: String, english: Entry, translated: Entry): List<String> {
    val wanted = english.forms.values.flatMap(::placeholders).toSet()
    return if (!english.plural) {
      val found = placeholders(translated.forms.getValue(""))
      if (found == wanted) {
        emptyList()
      } else {
        listOf("$where has placeholders $found, English has $wanted")
      }
    } else {
      // A form for one item may name it in words, so only "other" must use every placeholder.
      val other = translated.forms["other"]?.let(::placeholders).orEmpty()
      val extra = translated.forms.values.flatMap(::placeholders).toSet() - wanted
      buildList {
        if (other != wanted) add("$where[other] has placeholders $other, English has $wanted")
        if (extra.isNotEmpty()) add("$where has placeholders English lacks: $extra")
      }
    }
  }

  /** A string, or a plural with its [forms] by quantity. */
  private data class Entry(val plural: Boolean, val forms: Map<String, String>) {
    val kind: String get() = if (plural) "plural" else "string"
  }
}

/** The positions of the `%1$s`-style placeholders in [text]. */
private fun placeholders(text: String): Set<Int> =
  PLACEHOLDER.findAll(text).map { it.groupValues[1].toInt() }.toSet()

private val PLACEHOLDER = Regex("""%(\d+)\$[sd]""")

// %s, %d and %1s are not replaced by Compose resources.
private val BARE_PLACEHOLDER = Regex("""%(\d+[sd]|[sd])""")
