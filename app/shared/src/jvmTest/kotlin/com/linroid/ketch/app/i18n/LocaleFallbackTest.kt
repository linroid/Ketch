package com.linroid.ketch.app.i18n

import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_general_language_current
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks which translation each system language gets (docs/development/localization.md): the
 * name of the language the app shows, `settings_general_language_current`, read with the JVM's
 * default locale set to [Locale.forLanguageTag] of each tag.
 */
class LocaleFallbackTest {

  @Test fun eachTranslation_isShownInItsLanguage() = runTest {
    for (folder in translations()) assertShows(folder, tagOf(folder))
  }

  @Test fun languagesWithoutATranslation_fallBackToEnglish() = runTest {
    assertShows("values", "en-US", "en-GB", "it-IT", "nl", "ar-EG")
  }

  // Reads the language name with each of tags as the default locale.
  private suspend fun assertShows(folder: String, vararg tags: String) {
    val expected = languageName(folder)
    val saved = Locale.getDefault()
    try {
      for (tag in tags) {
        Locale.setDefault(Locale.forLanguageTag(tag))
        val shown = getString(
          getSystemResourceEnvironment(),
          Res.string.settings_general_language_current,
        )
        assertEquals(expected, shown, "$tag should show $folder")
      }
    } finally {
      Locale.setDefault(saved)
    }
  }

  // The translations' folders, such as values-ja and values-zh-rTW.
  private fun translations(): List<String> =
    RESOURCES.list().orEmpty().filter { it.startsWith("values-") }.sorted()

  // The language tag a folder is for: values-ja is ja, values-zh-rTW zh-TW and values-b+zh+Hant
  // zh-Hant.
  private fun tagOf(folder: String): String {
    val qualifier = folder.removePrefix("values-")
    if (qualifier.startsWith("b+")) return qualifier.removePrefix("b+").replace('+', '-')
    return qualifier.replace("-r", "-")
  }

  // The language name as the strings file of folder writes it.
  private fun languageName(folder: String): String {
    val file = File(RESOURCES, "$folder/strings_settings.xml")
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val strings = document.getElementsByTagName("string")
    for (i in 0 until strings.length) {
      val node = strings.item(i)
      if (node.attributes.getNamedItem("name")?.nodeValue == KEY) return node.textContent
    }
    error("$file has no $KEY")
  }

  private companion object {
    const val KEY = "settings_general_language_current"
    val RESOURCES = File("src/commonMain/composeResources")
  }
}
