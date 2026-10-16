package com.linroid.ketch.updater

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleasesBetweenTest {
  @Test
  fun releasesBetween_listsEveryReleaseAfterSinceUpToVersion() = runTest {
    val feed = PagedFeed(listOf("0.4.0", "0.3.2", "0.3.1", "0.3.1-rc1", "0.3.0", "0.2.4"))

    val releases = feed.releasesBetween(version("0.3.0"), version("0.3.2"))

    assertEquals(listOf("0.3.2", "0.3.1", "0.3.1-rc1"), releases.map { it.version.toString() })
  }

  @Test
  fun releasesBetween_stopsAtThePageThatReachesSince() = runTest {
    val feed = PagedFeed((20 downTo 1).map { "0.$it.0" }, pageSize = 3)

    feed.releasesBetween(version("0.17.0"), version("0.20.0"))

    assertEquals(listOf(1, 2), feed.pages)
  }

  @Test
  fun releasesBetween_readsAtMostTheMaximumPages() = runTest {
    val feed = PagedFeed((40 downTo 1).map { "0.$it.0" }, pageSize = 3)

    val releases = feed.releasesBetween(version("0.1.0"), version("0.40.0"))

    assertEquals((1..MAX_RELEASE_PAGES).toList(), feed.pages)
    assertEquals(MAX_RELEASE_PAGES * 3, releases.size)
  }

  @Test
  fun releasesBetween_versionNotListed_asksForItAlone() = runTest {
    val feed = PagedFeed(listOf("0.3.1", "0.3.0"))

    val releases = feed.releasesBetween(version("0.3.0"), version("0.3.2"))

    assertEquals(listOf("0.3.2", "0.3.1"), releases.map { it.version.toString() })
    assertEquals(listOf("0.3.2"), feed.asked)
  }

  @Test
  fun releasesBetween_withoutSince_readsOnlyTheVersion() = runTest {
    val feed = PagedFeed(listOf("0.3.2", "0.3.1"))

    val releases = feed.releasesBetween(null, version("0.3.2"))

    assertEquals(listOf("0.3.2"), releases.map { it.version.toString() })
    assertEquals(emptyList(), feed.pages)
  }

  @Test
  fun releasesBetween_sinceNotOlder_readsOnlyTheVersion() = runTest {
    val feed = PagedFeed(listOf("0.3.2", "0.3.1"))

    feed.releasesBetween(version("0.3.2"), version("0.3.2"))

    assertEquals(emptyList(), feed.pages)
  }

  @Test
  fun releasesFrom_listsFirstAndEveryNewerRelease() = runTest {
    val feed = PagedFeed(listOf("0.4.0", "0.3.1", "0.3.0", "0.2.4", "0.1.0", "0.0.5"))

    val releases = feed.releasesFrom(version("0.2.4"))

    assertEquals(listOf("0.4.0", "0.3.1", "0.3.0", "0.2.4"), releases.map { it.version.toString() })
    assertEquals(emptyList(), feed.asked)
  }

  @Test
  fun releasesFrom_stopsAtThePageListingAnOlderRelease() = runTest {
    val feed = PagedFeed((20 downTo 1).map { "0.$it.0" }, pageSize = 3)

    feed.releasesFrom(version("0.18.0"))

    assertEquals(listOf(1, 2), feed.pages)
  }

  @Test
  fun releasesFrom_readsMorePagesThanReleasesBetween() = runTest {
    val feed = PagedFeed((40 downTo 1).map { "0.$it.0" }, pageSize = 3)

    val releases = feed.releasesFrom(version("0.1.0"))

    assertEquals((1..15).toList(), feed.pages)
    assertEquals(40, releases.size)
  }

  @Test
  fun releasesFrom_nothingThatNew_listsNothing() = runTest {
    val feed = PagedFeed(listOf("0.0.5", "0.0.4"))

    assertEquals(emptyList(), feed.releasesFrom(version("0.1.0")))
    assertEquals(emptyList(), feed.asked)
  }

  private fun version(text: String) = ReleaseVersion.parse(text)!!

  /** Lists [versions] in pages of [pageSize]; records the pages read and releases asked for. */
  private class PagedFeed(versions: List<String>, private val pageSize: Int = 10) : ReleaseFeed {
    private val releases = versions.map(::release)
    val pages = mutableListOf<Int>()
    val asked = mutableListOf<String>()

    override suspend fun latest(): Release = releases.first()

    override suspend fun release(version: ReleaseVersion): Release {
      asked += version.toString()
      return release(version.toString())
    }

    override suspend fun releases(page: Int): List<Release> {
      pages += page
      return releases.drop((page - 1) * pageSize).take(pageSize)
    }

    private fun release(version: String) = Release(
      version = ReleaseVersion.parse(version)!!,
      pageUrl = "https://github.com/linroid/Ketch/releases/tag/v$version",
      assets = emptyList(),
    )
  }
}
