package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadCategory
import com.linroid.ketch.app.i18n.load
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryRulesTest {

  @Test
  fun parseExtensions_mixedSeparatorsAndPrefixes_cleanList() {
    assertEquals(
      listOf("mp4", "mkv", "tar.gz", "webm"),
      parseExtensions(" .MP4, *.mkv  tar.gz;webm,, mp4"),
    )
  }

  @Test
  fun parseMimeTypes_lowercasedOnce() {
    assertEquals(
      listOf("video/*", "application/pdf"),
      parseMimeTypes("Video/*, application/pdf video/*"),
    )
  }

  @Test
  fun isMimeTypeRule_needsTypeAndSubtype() {
    assertTrue(isMimeTypeRule("video/*"))
    assertFalse(isMimeTypeRule("video"))
    assertFalse(isMimeTypeRule("video/"))
    assertFalse(isMimeTypeRule("a/b/c"))
  }

  @Test
  fun parseHosts_pastedLinksAndWildcards_keepHostOnly() {
    assertEquals(
      listOf("github.com", "example.org", "cdn.example.net"),
      parseHosts("https://GitHub.com/linroid/Ketch, *.example.org, .cdn.example.net/path"),
    )
  }

  @Test
  fun normalizeCategoryFolder_spacesAndEmptyNames_dropped() {
    assertEquals("Video/Clips", normalizeCategoryFolder(" Video / Clips/ "))
  }

  @Test
  fun normalizeCategoryFolder_outsideDownloadFolder_rejected() {
    for (typed in listOf("", "  ", "/Video", "\\Video", "../Video", "Video/..", "C:\\Video")) {
      assertNull(normalizeCategoryFolder(typed), typed)
    }
  }

  @Test
  fun newCategoryFolder_nameTaken_numbersIt() {
    val taken = listOf(DownloadCategory(folder = "New folder"), DownloadCategory("new folder 2"))

    assertEquals("New folder 3", newCategoryFolder("New folder", taken))
    assertEquals("New folder", newCategoryFolder("New folder", emptyList()))
  }

  @Test
  fun suggestedCategories_eachExtensionInOneOnly() {
    val extensions = SuggestedCategory.entries.flatMap { it.extensions }

    assertEquals(extensions.distinct(), extensions)
  }

  @Test
  fun categorySummary_manyTypesAndSites_namesFirstAndCountsRest() = runTest {
    val category = DownloadCategory(
      folder = "Video",
      extensions = listOf("mp4", "mkv", "webm", "mov", "avi"),
      mimeTypes = listOf("video/*"),
      hosts = listOf("example.com"),
    )

    assertEquals("mp4, mkv, webm, mov +2 · From example.com", categorySummary(category).load())
  }

  @Test
  fun categorySummary_noRules_asksForOne() = runTest {
    assertEquals(
      "Add an extension, media type or site",
      categorySummary(DownloadCategory(folder = "Empty")).load(),
    )
  }
}
