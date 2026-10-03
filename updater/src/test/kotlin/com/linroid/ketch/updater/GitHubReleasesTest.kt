package com.linroid.ketch.updater

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubReleasesTest {
  private val api = "https://api.test"

  @Test
  fun latest_readsVersionPageAndDigests() = runTest {
    val engine = FakeHttpEngine(mapOf("$api/repos/linroid/Ketch/releases/latest" to RELEASE_JSON))
    val release = GitHubReleases({ engine }, apiUrl = api).latest()

    assertEquals(ReleaseVersion(0, 0, 2, "rc1"), release.version)
    assertEquals("https://github.com/linroid/Ketch/releases/tag/v0.0.2-rc1", release.pageUrl)
    val (cli, notes) = release.assets
    assertEquals("ketch-cli-0.0.2-rc1-linux-x64.tar.gz", cli.name)
    assertEquals(41, cli.size)
    assertEquals("ab12ef", cli.sha256)
    // Files uploaded before GitHub computed digests have none.
    assertNull(notes.sha256)
  }

  @Test
  fun latest_sendsTheHeadersGitHubRequires() = runTest {
    val engine = FakeHttpEngine(mapOf("$api/repos/linroid/Ketch/releases/latest" to RELEASE_JSON))
    GitHubReleases({ engine }, apiUrl = api).latest()

    val headers = engine.requests.single().second
    assertEquals("application/vnd.github+json", headers["Accept"])
    assertContains(headers.getValue("User-Agent"), "Ketch/")
    assertTrue(engine.closed)
  }

  @Test
  fun release_readsTheVersionsTag() = runTest {
    val engine = FakeHttpEngine(
      mapOf("$api/repos/linroid/Ketch/releases/tags/v0.0.2-rc1" to RELEASE_JSON),
    )
    val release = GitHubReleases({ engine }, apiUrl = api).release(ReleaseVersion(0, 0, 2, "rc1"))

    assertEquals(ReleaseVersion(0, 0, 2, "rc1"), release.version)
  }

  @Test
  fun release_missing_namesTheVersion() = runTest {
    val feed = GitHubReleases({ FakeHttpEngine(emptyMap()) }, apiUrl = api)
    val error = assertFailsWith<UpdateException> { feed.release(ReleaseVersion(9, 9, 9)) }
    assertContains(error.message.orEmpty(), "no release v9.9.9")
  }

  @Test
  fun latest_refused_explainsTheRateLimit() = runTest {
    val feed = GitHubReleases({ FakeHttpEngine(emptyMap(), missingCode = 403) }, apiUrl = api)
    val error = assertFailsWith<UpdateException> { feed.latest() }
    assertContains(error.message.orEmpty(), "try again later")
  }

  @Test
  fun latest_tagIsNotAVersion_fails() = runTest {
    val body = """{"tag_name": "nightly", "html_url": "https://x"}""".encodeToByteArray()
    val engine = FakeHttpEngine(mapOf("$api/repos/linroid/Ketch/releases/latest" to body))
    val feed = GitHubReleases({ engine }, apiUrl = api)
    val error = assertFailsWith<UpdateException> { feed.latest() }
    assertContains(error.message.orEmpty(), "nightly")
  }

  @Test
  fun latest_notJson_fails() = runTest {
    val body = "<html>".encodeToByteArray()
    val engine = FakeHttpEngine(mapOf("$api/repos/linroid/Ketch/releases/latest" to body))
    assertFailsWith<UpdateException> { GitHubReleases({ engine }, apiUrl = api).latest() }
  }

  private companion object {
    val RELEASE_JSON = """
      {
        "tag_name": "v0.0.2-rc1",
        "html_url": "https://github.com/linroid/Ketch/releases/tag/v0.0.2-rc1",
        "draft": false,
        "assets": [
          {
            "name": "ketch-cli-0.0.2-rc1-linux-x64.tar.gz",
            "browser_download_url": "https://github.com/x.tar.gz",
            "size": 41,
            "digest": "sha256:AB12EF"
          },
          {
            "name": "notes.txt",
            "browser_download_url": "https://github.com/notes.txt",
            "size": 3,
            "digest": null
          }
        ]
      }
    """.trimIndent().encodeToByteArray()
  }
}
