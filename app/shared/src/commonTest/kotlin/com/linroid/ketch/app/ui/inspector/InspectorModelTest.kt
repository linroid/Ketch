package com.linroid.ketch.app.ui.inspector

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.ListFixtures.row
import com.linroid.ketch.app.state.RowCapabilities
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.util.RowContext
import com.linroid.ketch.app.util.TaskOrigin
import com.linroid.ketch.app.util.rowContent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class InspectorModelTest {
  private val start = ListFixtures.START

  @Test
  fun inspectorReason_stalledDownload_offersReconnect() = runTest {
    val reason = inspectorReason(stalled(), slowLane = false, globalCap = SpeedLimit.Unlimited)

    assertEquals(ReasonAction.Reconnect, reason?.action)
    assertTrue(reason!!.warning)
    assertTrue(reason.text.load().startsWith("Stalled"))
  }

  @Test
  fun inspectorReason_slowLaneBelowOwnLimit_offersFullSpeed() = runTest {
    val row = downloading(SpeedLimit.mbps(8))

    val reason = inspectorReason(row, slowLane = true, globalCap = SpeedLimit.mbps(1))

    assertEquals(ReasonAction.FullSpeed, reason?.action)
    assertEquals("Limited by Slow lane (1 MB/s)", reason?.text.load())
  }

  @Test
  fun inspectorReason_ownLimitLowerThanGlobal_offersRemoveLimit() = runTest {
    val row = downloading(SpeedLimit.kbps(512))

    val reason = inspectorReason(row, slowLane = true, globalCap = SpeedLimit.mbps(1))

    assertEquals(ReasonAction.RemoveLimit, reason?.action)
    assertEquals("Limited to 512 KB/s", reason?.text.load())
  }

  @Test
  fun inspectorReason_globalLimitWithoutSlowLane_opensSpeedSettings() = runTest {
    val reason = inspectorReason(downloading(), slowLane = false, globalCap = SpeedLimit.mbps(5))

    assertEquals(ReasonAction.SpeedSettings, reason?.action)
    assertEquals("Limited by the global limit (5 MB/s)", reason?.text?.load())
  }

  @Test
  fun inspectorReason_unlimitedDownload_isNull() {
    assertNull(inspectorReason(downloading(), slowLane = false, globalCap = SpeedLimit.Unlimited))
  }

  @Test
  fun inspectorReason_queued_explainsTheWaitWithoutChip() {
    val queued = row("q", DownloadState.Queued)

    val reason = inspectorReason(queued, slowLane = false, globalCap = SpeedLimit.Unlimited)

    assertEquals(queued.content.detail, reason?.text)
    assertNull(reason?.action)
  }

  @Test
  fun reason_preempted_showsRowDetail() = runTest {
    val preempted = row(
      id = "p",
      state = DownloadState.Paused(DownloadProgress(40, 100), PauseReason.Preempted("urgent")),
    )

    val reason = inspectorReason(preempted, slowLane = false, globalCap = SpeedLimit.Unlimited)

    assertEquals(
      "Paused for an urgent download · resumes automatically",
      reason?.text?.load()
    )
    assertNull(reason?.action)
  }

  @Test
  fun inspectorReason_pausedWithoutResumeSupport_saysItStartsOver() = runTest {
    val source = ResolvedSource("https://example.com/a.bin", "http", 100, false, "a.bin", 1)
    val paused = row(
      id = "p",
      state = DownloadState.Paused(DownloadProgress(40, 100)),
      request = DownloadRequest("https://example.com/a.bin", resolvedSource = source),
    )

    val reason = inspectorReason(paused, slowLane = false, globalCap = SpeedLimit.Unlimited)

    assertEquals("Paused · resuming starts over", reason?.text?.load())
  }

  @Test
  fun inspectorReason_completedFileMissing_warnsOnlyOnLocalDevices() = runTest {
    val state = DownloadState.Completed("/tmp/a.bin", 100)
    val local = row("a", state)
    val remote = row("b", state, device = DeviceInfo(verbatim("NAS"), RowCapabilities.remote()))

    val reason = inspectorReason(local, false, SpeedLimit.Unlimited, fileMissing = true)

    assertEquals("File moved or deleted", reason?.text.load())
    assertTrue(reason!!.warning)
    assertNull(reason.action)
    assertNull(inspectorReason(remote, false, SpeedLimit.Unlimited, fileMissing = true))
    assertNull(inspectorReason(local, false, SpeedLimit.Unlimited))
  }

  @Test
  fun metricParts_downloading_listsShareBytesSpeedTimeLeftAndFinish() = runTest {
    val row = row(
      id = "a",
      state = DownloadState.Downloading(DownloadProgress(42 * MB, 100 * MB, MB)),
      speedSamples = listOf(MB),
    )

    val parts = metricParts(row, start, TimeZone.UTC)

    assertEquals(
      listOf("42%", "42.0 of 100 MB", "1.0 MB/s", "58s left", "done ≈ 12:00"),
      parts.map { it.text }.load(),
    )
    assertEquals(listOf(true, false, true, false, false), parts.map { it.strong })
  }

  @Test
  fun metricParts_pausedBelowAUnitOfTheSize_writesBothUnits() = runTest {
    val state = DownloadState.Paused(DownloadProgress(497_025_024, 1_342_177_280))

    val parts = metricParts(row("p", state), start, TimeZone.UTC)

    assertEquals(listOf("37%", "474 MB of 1.3 GB"), parts.map { it.text }.load())
  }

  @Test
  fun metricParts_completed_leavesTheSummaryToTheSublineAndDetails() {
    val state = DownloadState.Completed("/tmp/a.bin", 100 * MB, downloadTime = 10.seconds)

    assertEquals(emptyList(), metricParts(row("a", state), start, TimeZone.UTC))
  }

  @Test
  fun metricParts_queuedWithoutKnownSize_isEmpty() {
    assertEquals(emptyList(), metricParts(row("q", DownloadState.Queued), start, TimeZone.UTC))
  }

  @Test
  fun addedDetail_earlierDay_addsTheTime() = runTest {
    val today = row("t", DownloadState.Queued, createdAt = start - 2.hours)
    val yesterday = row("y", DownloadState.Queued, createdAt = start - 18.hours)

    assertEquals("Today 10:00", addedDetail(today, start, TimeZone.UTC).load())
    assertEquals("Yesterday 18:00", addedDetail(yesterday, start, TimeZone.UTC).load())
  }

  @Test
  fun finishedDetail_completedEarlierDay_addsTheTime() = runTest {
    val today = DownloadState.Completed("/d/t.iso", 10, completedAt = start - 2.hours)
    val yesterday = DownloadState.Completed("/d/y.iso", 10, completedAt = start - 18.hours)
    val unknown = DownloadState.Completed("/d/u.iso", 10)

    assertEquals("Today 10:00", finishedDetail(row("t", today), start, TimeZone.UTC)?.load())
    assertEquals(
      "Yesterday 18:00",
      finishedDetail(row("y", yesterday), start, TimeZone.UTC)?.load()
    )
    assertNull(finishedDetail(row("u", unknown), start, TimeZone.UTC))
  }

  @Test
  fun sourceLabel_eachKindOfLink_namesProtocolAndHost() = runTest {
    assertEquals(
      "HTTPS · releases.ubuntu.com",
      sourceLabel(DownloadRequest("https://releases.ubuntu.com/a.iso"), isTorrent = false).load()
    )
    assertEquals(
      "FTP · ftp.example.org",
      sourceLabel(DownloadRequest("ftp://user:pw@ftp.example.org/a.bin"), isTorrent = false).load()
    )
    assertEquals(
      "BitTorrent · magnet",
      sourceLabel(DownloadRequest("magnet:?xt=urn:btih:abc"), isTorrent = true).load()
    )
    assertEquals(
      "BitTorrent · torrent file",
      sourceLabel(DownloadRequest("https://example.com/a.torrent?x=1"), isTorrent = true).load()
    )
  }

  @Test
  fun linkParts_signedLink_keepsTheQueryApart() {
    val parts = linkParts("https://cdn.example.com:8443/files/a.iso?X-Amz-Signature=abc#top")

    assertEquals("cdn.example.com:8443", parts.host)
    assertEquals("/files/a.iso", parts.path)
    assertEquals("?X-Amz-Signature=abc#top", parts.query)
  }

  @Test
  fun linkParts_credentials_neverShowThePassword() {
    val parts = linkParts("ftp://alex:secret@ftp.example.org/pub/a.bin")

    assertEquals("ftp.example.org", parts.host)
    assertEquals("ftp://alex:***@ftp.example.org/pub/a.bin", parts.full)
    assertNull(parts.query)
  }

  @Test
  fun linkParts_magnet_keepsTheFirstParameterInView() {
    val parts = linkParts("magnet:?xt=urn:btih:abc&dn=a.iso&tr=udp%3A%2F%2Ft")

    assertNull(parts.host)
    assertEquals("magnet:?xt=urn:btih:abc", parts.path)
    assertEquals("&dn=a.iso&tr=udp%3A%2F%2Ft", parts.query)
  }

  @Test
  fun capturedText_browserWithCookiesAndReferer_saysWhatCameAlong() = runTest {
    val request = DownloadRequest(
      url = "https://example.com/a.bin",
      headers = mapOf("Cookie" to "s=1", "Referer" to "https://example.com/", "User-Agent" to "x"),
      properties = mapOf(TaskOrigin.PROPERTY to "browser"),
    )

    assertEquals("From the browser, with cookies and referrer", capturedText(request).load())
  }

  @Test
  fun capturedText_headersWithoutOrigin_startsWithThem() = runTest {
    val request = DownloadRequest("https://example.com/a", headers = mapOf("referer" to "x"))

    assertEquals("With referrer", capturedText(request).load())
    assertNull(capturedText(DownloadRequest("https://example.com/a")))
  }

  @Test
  fun speedPresets_eightMegabytesPerSecond_offersFiveTwoAndOne() {
    assertEquals(
      listOf(SpeedLimit.mbps(5), SpeedLimit.mbps(2), SpeedLimit.mbps(1)),
      speedPresets(8_400 * 1024L)
    )
  }

  @Test
  fun speedPresets_slowerThanTheLadder_offersTheSlowestThree() {
    assertEquals(
      listOf(SpeedLimit.kbps(256), SpeedLimit.kbps(128), SpeedLimit.kbps(64)),
      speedPresets(50 * 1024L)
    )
  }

  @Test
  fun speedPresets_unknownSpeed_offersFiveTwoAndOne() {
    val expected = listOf(SpeedLimit.mbps(5), SpeedLimit.mbps(2), SpeedLimit.mbps(1))
    assertEquals(expected, speedPresets(null))
    assertEquals(expected, speedPresets(0))
  }

  @Test
  fun preemptionVictim_freeSlot_isNull() {
    assertNull(preemptionVictim(listOf(running("a", DownloadPriority.LOW)), slots = 2))
  }

  @Test
  fun preemptionVictim_selectedRowRunning_takesASlotButIsNeverPaused() {
    val selected = running("mine", DownloadPriority.LOW)
    val other = running("other", DownloadPriority.NORMAL)

    val starting = setOf(selected.key)

    val victim = preemptionVictim(listOf(selected, other), slots = 2, starting = starting)

    assertEquals("other.bin", victim?.name)
  }

  @Test
  fun preemptionVictim_slotsTaken_firstWithTheLowestPriority() {
    val running = listOf(
      running("a", DownloadPriority.HIGH),
      running("b", DownloadPriority.LOW),
      running("c", DownloadPriority.LOW),
      running("d", DownloadPriority.URGENT)
    )

    assertEquals("b.bin", preemptionVictim(running, slots = 4)?.name)
    assertNull(preemptionVictim(listOf(running("u", DownloadPriority.URGENT)), slots = 1))
  }

  @Test
  fun urgentNote_victim_namesItAndItsPriority() = runTest {
    val victim = running("debian-12", DownloadPriority.LOW)

    assertEquals("Starts now; may pause \"debian-12.bin\" (Low)", urgentNote(victim).load())
    assertEquals("Starts 2 now; may pause \"debian-12.bin\" (Low)", urgentNote(victim, 2).load())
  }

  @Test
  fun rescheduleNote_tonight_saysItPausesFirst() = runTest {
    val at = DownloadSchedule.AtTime(start + 13.hours)

    assertEquals(
      "Pauses now and starts 01:00 tonight",
      rescheduleNote(at, start, TimeZone.UTC).load(),
    )
  }

  @Test
  fun sharedSettings_differentValues_leaveThemOut() {
    val a = row("a", DownloadState.Queued, request = DownloadRequest("https://e.com/a"))
    val b = row(
      id = "b",
      state = DownloadState.Queued,
      request = DownloadRequest("https://e.com/b", speedLimit = SpeedLimit.mbps(1)),
    )

    val shared = SharedSettings.of(listOf(a, b))

    assertNull(shared.speedLimit)
    assertEquals(DownloadPriority.NORMAL, shared.priority)
    assertEquals(0, shared.connections)
  }

  @Test
  fun sharedSettings_scheduledRow_showsTheTimeItWaitsFor() {
    val at = DownloadSchedule.AtTime(start + 9.hours)
    val scheduled = row("s", DownloadState.Scheduled(at))
    val started = row(
      id = "r",
      state = DownloadState.Downloading(DownloadProgress(1, 10, 1)),
      request = DownloadRequest("https://e.com/r", schedule = DownloadSchedule.AtTime(start)),
    )

    assertEquals(at, SharedSettings.of(listOf(scheduled)).schedule)
    assertEquals(DownloadSchedule.Immediate, SharedSettings.of(listOf(started)).schedule)
  }

  @Test
  fun selectionLine_mixedRows_sumsKnownSizesAndSpeeds() = runTest {
    val rows = listOf(
      row(
        id = "a",
        state = DownloadState.Downloading(DownloadProgress(0, 2 * GB, MB)),
        speedSamples = listOf(MB),
      ),
      row("b", DownloadState.Completed("/tmp/b", GB)),
      row("c", DownloadState.Queued)
    )

    assertEquals("3 selected · 3.0 GB · 1.0 MB/s", selectionLine(rows).load())
  }

  private fun downloading(limit: SpeedLimit = SpeedLimit.Unlimited): TaskRow = row(
    id = "d",
    state = DownloadState.Downloading(DownloadProgress(10, 100, 10)),
    request = DownloadRequest("https://example.com/d.bin", speedLimit = limit),
  )

  private fun stalled(): TaskRow {
    val base = downloading()
    val context = RowContext(base.device, start, TimeZone.UTC)
    val content = rowContent(base.request, base.state, start, context, stalledFor = 12.seconds)
    return base.copy(content = content)
  }

  private fun running(id: String, priority: DownloadPriority): TaskRow = row(
    id = id,
    state = DownloadState.Downloading(DownloadProgress(1, 10, 1)),
    request = DownloadRequest("https://example.com/$id.bin", priority = priority),
  )

  @Test
  fun keepPartsTogether_shortAndLongParts_joinsOnlyTheShortOnes() {
    val text = keepPartsTogether("Downloading 4 files on 2 devices · all done ≈ 14:38")

    assertEquals(
      "Downloading 4\u00A0files on 2\u00A0devices\u00A0· all\u00A0done\u00A0≈\u00A014:38",
      text
    )
  }

  private companion object {
    const val MB = 1L shl 20
    const val GB = 1L shl 30
  }
}
