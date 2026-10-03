package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.app.i18n.load
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueReasonTest {
  private val queued = DownloadRequest("https://github.com/a/b.zip")

  @Test
  fun of_allSlotsTaken_waitsForSlot() = runTest {
    val config = DownloadConfig(maxConcurrentDownloads = 2)
    val running = listOf(request("https://a.com/1"), request("https://b.com/2"))

    val reason = QueueReason.of(queued, config, running)

    assertEquals(QueueReason.SlotsFull(running = 2, limit = 2), reason)
    assertEquals("Waiting for a free slot (2 of 2 in use)", reason.text.load())
  }

  @Test
  fun of_loweredSlotLimit_countsEveryRunningTask() = runTest {
    val config = DownloadConfig(maxConcurrentDownloads = 2)
    val running = List(3) { request("https://a.com/$it") }

    assertEquals(
      "Waiting for a free slot (3 of 2 in use)",
      QueueReason.of(queued, config, running).text.load()
    )
  }

  @Test
  fun of_hostLimitReached_waitsForHost() = runTest {
    val config = DownloadConfig(maxConcurrentDownloads = 10, maxConnectionsPerHost = 2)
    val running = listOf(
      request("https://GitHub.com/1"),
      request("https://user@github.com:443/2"),
      request("https://example.com/3")
    )

    val reason = QueueReason.of(queued, config, running)

    assertEquals(QueueReason.HostFull("github.com", 2), reason)
    assertEquals("Waiting for github.com (2 per site)", reason.text.load())
  }

  @Test
  fun of_slotsAndHostFull_reportsSlotsFirst() {
    val config = DownloadConfig(maxConcurrentDownloads = 2, maxConnectionsPerHost = 2)
    val running = List(2) { request("https://github.com/$it") }

    assertEquals(QueueReason.SlotsFull(2, 2), QueueReason.of(queued, config, running))
  }

  @Test
  fun of_unlimited_isNext() {
    val config = DownloadConfig(maxConcurrentDownloads = 0, maxConnectionsPerHost = 0)
    val running = List(20) { request("https://github.com/$it") }

    assertEquals(QueueReason.Next, QueueReason.of(queued, config, running))
  }

  @Test
  fun of_magnet_ignoresHostLimit() {
    val config = DownloadConfig(maxConcurrentDownloads = 10, maxConnectionsPerHost = 1)
    val magnet = request("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a")
    val running = listOf(request("magnet:?xt=urn:btih:0000000000000000000000000000000000000000"))

    assertEquals(QueueReason.Next, QueueReason.of(magnet, config, running))
  }

  @Test
  fun of_freeSlotAndHost_isNext() = runTest {
    val config = DownloadConfig(maxConcurrentDownloads = 2, maxConnectionsPerHost = 8)

    val reason = QueueReason.of(queued, config, listOf(request("https://github.com/1")))

    assertEquals(QueueReason.Next, reason)
    assertEquals("Waiting to start", reason.text.load())
  }

  private fun request(url: String) = DownloadRequest(url)
}
