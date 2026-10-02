package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class DownloadSearchTest {
  private val now = Instant.parse("2026-10-01T12:00:00Z")
  private val downloading = DownloadState.Downloading(DownloadProgress(10, 100, 5))

  @Test
  fun parse_tokensAndText_splitsThem() {
    val query = SearchQuery.parse("  ubuntu IS:Paused type:video  desktop ")

    assertEquals(listOf("ubuntu", "desktop"), query.terms)
    assertEquals(
      listOf(SearchToken.Is(SearchStatus.Paused), SearchToken.Type(FileType.Video)),
      query.tokens
    )
  }

  @Test
  fun parse_unknownKeyOrValue_keepsWordAsText() {
    val query = SearchQuery.parse("https://example.com/a.iso is:sleeping foo:bar size:big :x")

    assertEquals(
      listOf("https://example.com/a.iso", "is:sleeping", "foo:bar", "size:big", ":x"),
      query.terms
    )
    assertTrue(query.tokens.isEmpty())
  }

  @Test
  fun parse_quotes_keepPhrasesAndValuesTogether() {
    val query = SearchQuery.parse("""device:"This Mac" "big buck" "is:paused"""")

    assertEquals(listOf(SearchToken.Device("This Mac")), query.tokens)
    assertEquals(listOf("big buck", "is:paused"), query.terms)
  }

  @Test
  fun parse_duplicateToken_keepsOne() {
    assertEquals(1, SearchQuery.parse("is:done is:DONE").tokens.size)
  }

  @Test
  fun parse_hostValue_normalizesToHost() {
    assertEquals(
      listOf(SearchToken.Host("github.com"), SearchToken.Host("cdn.example.com")),
      SearchQuery.parse("host:https://GitHub.com/x host:cdn.example.com/files").tokens
    )
  }

  @Test
  fun format_parsedQuery_roundTrips() {
    val query = SearchQuery.parse(
      """report device:"NAS Basement" size:>1.5gb "is:paused" added:<7d origin:browser"""
    )

    assertEquals(query, SearchQuery.parse(query.format()))
    assertEquals("""report "is:paused"""", query.text)
  }

  @Test
  fun matches_text_searchesDecodedFields() {
    val target = Target(
      name = "Big Buck Bunny.mkv",
      host = "cdn.example.com",
      refererHost = "videos.example.org",
      outputPath = decodePath("content://downloads/document/primary%3AMovies%2FBunny.mkv"),
      errorTitle = "Access denied (403)",
    )

    assertTrue(query("buck bunny").matches(target, now, UTC))
    assertTrue(query("CDN.EXAMPLE").matches(target, now, UTC))
    assertTrue(query("videos.example.org").matches(target, now, UTC))
    assertTrue(query("Movies/Bunny").matches(target, now, UTC))
    assertTrue(query("denied").matches(target, now, UTC))
    assertFalse(query("bunny holiday").matches(target, now, UTC))
  }

  @Test
  fun matches_isState_usesStatusFilterDefinitions() {
    val scheduled = Target(state = DownloadState.Scheduled(DownloadSchedule.AtTime(now)))
    val canceled = Target(state = DownloadState.Canceled)

    assertTrue(query("is:downloading").matches(Target(state = downloading), now, UTC))
    assertTrue(query("is:waiting").matches(Target(state = DownloadState.Queued), now, UTC))
    assertTrue(query("is:waiting").matches(scheduled, now, UTC))
    assertTrue(query("is:scheduled").matches(scheduled, now, UTC))
    assertFalse(query("is:scheduled").matches(Target(state = DownloadState.Queued), now, UTC))
    assertTrue(query("is:failed").matches(canceled, now, UTC))
    assertTrue(query("is:done").matches(Target(state = completed()), now, UTC))
    assertFalse(query("is:paused").matches(Target(state = downloading), now, UTC))
  }

  @Test
  fun matches_isFlags_checkPriorityStallAndLimit() {
    val urgent = Target(priority = DownloadPriority.URGENT)

    assertTrue(query("is:urgent").matches(urgent, now, UTC))
    assertFalse(query("is:urgent").matches(Target(), now, UTC))
    assertTrue(query("is:stalled").matches(Target(isStalled = true), now, UTC))
    assertTrue(query("is:limited").matches(Target(isLimited = true), now, UTC))
  }

  @Test
  fun matches_tokensOfOneFacet_widenAndFlagsNarrow() {
    val video = Target(fileType = FileType.Video, state = downloading)
    val audio = Target(fileType = FileType.Audio, priority = DownloadPriority.URGENT)

    assertTrue(query("type:video type:audio").matches(video, now, UTC))
    assertTrue(query("type:video type:audio").matches(audio, now, UTC))
    assertFalse(query("type:video host:example.com").matches(video, now, UTC))
    assertTrue(query("is:downloading is:paused").matches(video, now, UTC))
    assertFalse(query("is:urgent is:downloading").matches(video, now, UTC))
    assertFalse(query("is:urgent is:stalled").matches(audio, now, UTC))
  }

  @Test
  fun matches_typeTorrent_findsTorrentsOfAnyType() {
    val torrentVideo = Target(fileType = FileType.Video, isTorrent = true)

    assertTrue(query("type:torrent").matches(torrentVideo, now, UTC))
    assertTrue(query("type:video").matches(torrentVideo, now, UTC))
    assertFalse(query("type:torrent").matches(Target(fileType = FileType.Video), now, UTC))
  }

  @Test
  fun matches_hostToken_matchesSubdomainsAndReferer() {
    val target = Target(host = "objects.githubusercontent.com", refererHost = "github.com")

    assertTrue(query("host:github.com").matches(target, now, UTC))
    assertTrue(query("host:githubusercontent.com").matches(target, now, UTC))
    assertFalse(query("host:hub.com").matches(target, now, UTC))
    assertFalse(query("host:github.com").matches(Target(host = null), now, UTC))
  }

  @Test
  fun matches_originToken_skipsTasksOfUnknownOrigin() {
    assertTrue(query("origin:browser").matches(Target(origin = TaskOrigin.Browser), now, UTC))
    assertFalse(query("origin:browser").matches(Target(origin = TaskOrigin.Cli), now, UTC))
    assertFalse(query("origin:browser").matches(Target(origin = null), now, UTC))
  }

  @Test
  fun matches_deviceToken_findsNamesContainingIt() {
    assertTrue(query("device:nas").matches(Target(deviceName = "NAS-Basement"), now, UTC))
    assertFalse(query("device:nas").matches(Target(deviceName = "This Mac"), now, UTC))
  }

  @Test
  fun matches_sizeToken_comparesInBinaryUnits() {
    val gigabyte = 1L shl 30
    val big = Target(sizeBytes = 2 * gigabyte)
    val exact = Target(sizeBytes = gigabyte)

    assertTrue(query("size:>1gb").matches(big, now, UTC))
    assertFalse(query("size:>1gb").matches(exact, now, UTC))
    assertTrue(query("size:1g").matches(exact, now, UTC))
    assertTrue(query("size:<=1024mib").matches(exact, now, UTC))
    assertTrue(query("size:<1.5GB").matches(exact, now, UTC))
    assertFalse(query("size:<1.5GB").matches(big, now, UTC))
    assertFalse(query("size:>0").matches(Target(sizeBytes = null), now, UTC))
  }

  @Test
  fun matches_sizeBounds_bothMustHold() {
    val range = query("size:>1mb size:<1gb")

    assertTrue(range.matches(Target(sizeBytes = 5L shl 20), now, UTC))
    assertFalse(range.matches(Target(sizeBytes = 2L shl 30), now, UTC))
  }

  @Test
  fun matches_addedToken_usesLocalDaysAndAges() {
    // 01:30 local on Oct 1 in UTC+2 is 23:30 UTC on Sep 30.
    val zone = UtcOffset(hours = 2).asTimeZone()
    val localNow = Instant.parse("2026-09-30T23:30:00Z")
    val lateYesterday = Target(createdAt = Instant.parse("2026-09-30T21:59:00Z"))
    val earlyToday = Target(createdAt = Instant.parse("2026-09-30T22:01:00Z"))

    assertTrue(query("added:today").matches(earlyToday, localNow, zone))
    assertFalse(query("added:today").matches(lateYesterday, localNow, zone))
    assertTrue(query("added:yesterday").matches(lateYesterday, localNow, zone))
  }

  @Test
  fun matches_addedSpan_withinOrOlder() {
    val recent = Target(createdAt = now - 3.days)
    val old = Target(createdAt = now - 10.days)

    assertTrue(query("added:<7d").matches(recent, now, UTC))
    assertTrue(query("added:7d").matches(recent, now, UTC))
    assertFalse(query("added:<7d").matches(old, now, UTC))
    assertTrue(query("added:>1w").matches(old, now, UTC))
    assertFalse(query("added:<12h").matches(Target(createdAt = now - 13.hours), now, UTC))
  }

  @Test
  fun plusAndMinus_editTokens() {
    val host = SearchToken.Host("github.com")
    val query = SearchQuery.parse("ubuntu") + host

    assertEquals(query, query + host)
    assertEquals(SearchQuery.parse("ubuntu"), query - host)
    assertEquals("host:github.com ubuntu", query.format())
  }

  @Test
  fun fileType_of_foldsKindsIntoEightTypes() {
    assertEquals(FileType.Video, FileType.of(FileKind.Subtitle))
    assertEquals(FileType.Doc, FileType.of(FileKind.Pdf))
    assertEquals(FileType.Archive, FileType.of(FileKind.DiskImage))
    assertEquals(FileType.Other, FileType.of(FileKind.Code))
    assertEquals(8, FileKind.entries.map(FileType::of).distinct().size)
  }

  @Test
  fun taskOrigin_of_readsTheRequestProperty() {
    val request = DownloadRequest("https://a.com/x", properties = mapOf("ketch.origin" to "CLI"))

    assertEquals(TaskOrigin.Cli, TaskOrigin.of(request))
    assertNull(TaskOrigin.of(DownloadRequest("https://a.com/x")))
  }

  private fun query(text: String) = SearchQuery.parse(text)

  private fun completed() = DownloadState.Completed("/downloads/a.iso", 100)

  private data class Target(
    override val name: String = "file.bin",
    override val host: String? = "example.org",
    override val refererHost: String? = null,
    override val outputPath: String? = null,
    override val errorTitle: String? = null,
    override val state: DownloadState = DownloadState.Failed(KetchError.Network()),
    override val priority: DownloadPriority = DownloadPriority.NORMAL,
    override val isStalled: Boolean = false,
    override val isLimited: Boolean = false,
    override val fileType: FileType = FileType.Other,
    override val isTorrent: Boolean = false,
    override val origin: TaskOrigin? = null,
    override val deviceName: String = "This Mac",
    override val sizeBytes: Long? = null,
    override val createdAt: Instant = Instant.parse("2026-10-01T09:00:00Z"),
  ) : SearchTarget

  private companion object {
    val UTC: TimeZone = TimeZone.UTC
  }
}
