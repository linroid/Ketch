package com.linroid.ketch.app.ui.intake

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.state.IntakeEntry
import com.linroid.ketch.app.state.IntakeSource
import com.linroid.ketch.app.state.IntakeStatus
import com.linroid.ketch.app.state.ListTestTask
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class IntakeCopyTest {

  private val now = Instant.parse("2026-10-01T12:00:00Z")

  @Test
  fun statusLine_readyLink_listsSizeSchemeResumeAndConnections() = runTest {
    val entry = IntakeEntry(IntakeSource.Link("https://releases.example.com/a.iso"))
    entry.status = IntakeStatus.Ready(source(maxSegments = 16))

    val line = statusLine(entry, now)

    assertEquals("5.70 GB · HTTPS · resumable · up to 16 connections", line.text.load())
    assertEquals(LineTone.Neutral, line.tone)
    assertEquals(null, line.badge)
  }

  @Test
  fun statusLine_serverWithoutResume_showsTheNoResumePill() = runTest {
    val entry = IntakeEntry(IntakeSource.Link("http://legacy.example.net/fw.bin"))
    entry.status = IntakeStatus.Ready(source(maxSegments = 1, resume = false))

    val line = statusLine(entry, now)

    assertEquals("No resume", line.badge.load())
    assertEquals("5.70 GB · HTTP", line.text.load())
  }

  @Test
  fun statusLine_magnetWaiting_countsTheSeconds() = runTest {
    val entry = IntakeEntry(IntakeSource.Link("magnet:?xt=urn:btih:abc"))
    entry.status = IntakeStatus.Checking(now - 14.seconds)

    assertEquals("Fetching file list from peers… 0:14", statusLine(entry, now).text.load())
  }

  @Test
  fun statusLine_range_saysWhatItExpandsTo() = runTest {
    val urls = (1..4).map { "https://cdn.example.org/part0$it.rar" }
    val entry = IntakeEntry(IntakeSource.Range("https://cdn.example.org/part[01-04].rar", urls))
    entry.status = IntakeStatus.Ready(source(totalBytes = 1_181_116_006))

    assertEquals("4 × 1.10 GB · expands to 4 links", statusLine(entry, now).text.load())
    assertEquals("part01.rar … part04.rar", entry.name)
  }

  @Test
  fun statusLine_duplicateAddedAgain_saysSoBeforeTheCheck() = runTest {
    val entry = IntakeEntry(IntakeSource.Link("https://releases.example.com/a.iso"))
    entry.duplicate = ListTestTask(
      taskId = "a",
      state = DownloadState.Queued,
      request = DownloadRequest("https://releases.example.com/a.iso"),
      createdAt = now,
    )

    val left = statusLine(entry, now)
    entry.downloadAgain = true
    val again = statusLine(entry, now)

    assertEquals("Already in Ketch" to "waiting to start", left.badge.load() to left.text.load())
    assertEquals("Downloads again · Waiting to check…", again.text.load())
  }

  @Test
  fun duplicateState_finishedTask_saysWhenItFinished() = runTest {
    val task = ListTestTask(
      taskId = "a",
      state = DownloadState.Completed("/d/a.iso", downloadTime = 5.minutes),
      request = DownloadRequest("https://a.example/a.iso"),
      createdAt = now - 2.days - 5.minutes,
    )

    assertEquals("finished 2 days ago", duplicateState(task, now).load())
  }

  @Test
  fun relativeAge_ages_readNaturally() = runTest {
    val zone = TimeZone.UTC
    assertEquals("just now", relativeAge(now - 20.seconds, now, zone).load())
    assertEquals("5 min ago", relativeAge(now - 5.minutes, now, zone).load())
    assertEquals("yesterday", relativeAge(now - 1.days, now, zone).load())
    assertEquals("Sep 20", relativeAge(now - 11.days, now, zone).load())
  }

  @Test
  fun splitLabel_connections_divideTheSize() = runTest {
    assertEquals("16 × 360.0 MB", splitLabel(16L * 360 * 1024 * 1024, 16).load())
    assertEquals(null, splitLabel(-1, 8))
  }

  private fun source(
    maxSegments: Int = 8,
    resume: Boolean = true,
    totalBytes: Long = 6_120_328_397,
  ) = ResolvedSource(
    url = "https://releases.example.com/a.iso",
    sourceType = "http",
    totalBytes = totalBytes,
    supportsResume = resume,
    suggestedFileName = "a.iso",
    maxSegments = maxSegments,
  )
}
