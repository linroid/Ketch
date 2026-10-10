package com.linroid.ketch.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadCategoryTest {

  private val video = DownloadCategory(
    folder = "Video",
    extensions = listOf("mp4", "mkv"),
    mimeTypes = listOf("video/*"),
  )

  @Test
  fun matches_extension_ignoresCase() {
    assertTrue(video.matches("Clip.MP4", contentType = null, host = null))
  }

  @Test
  fun matches_mimeTypeOfKind_matchesWithoutExtension() {
    assertTrue(video.matches("watch", contentType = "video/webm", host = "example.com"))
  }

  @Test
  fun matches_mimeTypeWithParameters_comparesTypeOnly() {
    val docs = DownloadCategory(folder = "Docs", mimeTypes = listOf("Application/PDF"))

    assertTrue(docs.matches("file", contentType = "application/pdf; charset=binary", host = null))
  }

  @Test
  fun matches_otherTypeAndExtension_doesNotMatch() {
    assertFalse(video.matches("notes.txt", contentType = "text/plain", host = null))
  }

  @Test
  fun matches_kindRule_doesNotMatchKindPrefix() {
    val text = DownloadCategory(folder = "Text", mimeTypes = listOf("text/*"))

    assertFalse(text.matches("x", contentType = "textual/x", host = null))
  }

  @Test
  fun matches_compoundExtension_matchesWholeSuffix() {
    val tarballs = DownloadCategory(folder = "Tarballs", extensions = listOf(".tar.gz"))

    assertTrue(tarballs.matches("src.tar.gz", contentType = null, host = null))
    assertFalse(tarballs.matches("src.gz", contentType = null, host = null))
  }

  @Test
  fun matches_nameThatIsOnlyTheExtension_doesNotMatch() {
    assertFalse(video.matches(".mp4", contentType = null, host = null))
  }

  @Test
  fun matches_wildcardExtensionRule_ignoresWildcard() {
    val iso = DownloadCategory(folder = "ISO", extensions = listOf("*.iso"))

    assertTrue(iso.matches("ubuntu.iso", contentType = null, host = null))
  }

  @Test
  fun matches_host_coversSubdomainsButNotLookAlikes() {
    val github = DownloadCategory(folder = "GitHub", hosts = listOf("*.GitHub.com"))

    assertTrue(github.matches("a.zip", contentType = null, host = "github.com"))
    assertTrue(github.matches("a.zip", contentType = null, host = "codeload.github.com"))
    assertFalse(github.matches("a.zip", contentType = null, host = "notgithub.com"))
  }

  @Test
  fun matches_hostRuleWithoutHost_doesNotMatch() {
    val github = DownloadCategory(folder = "GitHub", hosts = listOf("github.com"))

    assertFalse(github.matches("ubuntu.iso", contentType = null, host = null))
  }

  @Test
  fun matches_typeAndHostRules_needBoth() {
    val videoFromSite = video.copy(hosts = listOf("example.com"))

    assertTrue(videoFromSite.matches("a.mp4", contentType = null, host = "cdn.example.com"))
    assertFalse(videoFromSite.matches("a.mp4", contentType = null, host = "other.org"))
    assertFalse(videoFromSite.matches("a.zip", contentType = null, host = "example.com"))
  }

  @Test
  fun matches_noRules_matchesNothing() {
    val empty = DownloadCategory(folder = "Other", extensions = listOf(" "))

    assertFalse(empty.matches("a.mp4", contentType = "video/mp4", host = "example.com"))
  }

  @Test
  fun categoryFor_severalMatch_returnsFirst() {
    val github = DownloadCategory(folder = "GitHub", hosts = listOf("github.com"))
    val config = DownloadConfig(categories = listOf(github, video))

    assertEquals(github, config.categoryFor("demo.mp4", contentType = null, host = "github.com"))
    assertEquals(video, config.categoryFor("demo.mp4", contentType = null, host = "example.com"))
    assertNull(config.categoryFor("demo.zip", contentType = null, host = "example.com"))
  }

  @Test
  fun init_nestedFolder_isAccepted() {
    DownloadCategory(folder = "Video/Clips", extensions = listOf("mp4"))
  }

  @Test
  fun init_folderOutsideDownloadFolder_throws() {
    for (folder in listOf("", " ", "/etc", "\\Windows", "C:\\Users", "c:Temp", "../up",
      "Video/../../up", "Video\\..", "content://tree")) {
      assertFailsWith<IllegalArgumentException>(folder) { DownloadCategory(folder = folder) }
    }
  }

  @Test
  fun decode_configWithoutCategories_hasNone() {
    val decoded = Json.decodeFromString(DownloadConfig.serializer(), """{"retryCount":1}""")

    assertEquals(emptyList(), decoded.categories)
  }
}
