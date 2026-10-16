package com.linroid.ketch.cli

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TaskOutputTest {

  @Test
  fun `displayName prefers the saved file, then the destination, then the URL`() {
    val completed = FakeTask("a", request("https://x.test/a"), DownloadState.Completed("/d/b.iso"))
    val named = FakeTask(
      "b",
      request("https://x.test/a", Destination("/d/c.iso")),
      DownloadState.Queued,
    )
    val folder = FakeTask("c", request("https://x.test/a%20b+c.iso", Destination("/d/")), queued)

    assertEquals("b.iso", displayName(completed))
    assertEquals("c.iso", displayName(named))
    assertEquals("a b+c.iso", displayName(folder))
  }

  @Test
  fun `displayName of a magnet link is its display name`() {
    val magnet = request("magnet:?xt=urn:btih:abcdef&dn=Big+Buck+Bunny&tr=udp%3A%2F%2Ft")

    assertEquals("Big Buck Bunny", displayName(FakeTask("m", magnet, queued)))
    assertEquals("x.test", displayName(FakeTask("h", request("https://x.test/"), queued)))
  }

  @Test
  fun `the table shows short IDs, progress and why a task waits`() {
    val tasks = listOf(
      FakeTask(
        "3f2a9c1e-aaaa", request("https://x.test/a.iso"),
        DownloadState.Downloading(DownloadProgress(512, 1024, 2048)),
      ),
      FakeTask(
        "9c1e3f2a-bbbb", request("https://x.test/b.iso"),
        DownloadState.Paused(DownloadProgress(0, 0), PauseReason.Preempted("3f2a9c1e-aaaa")),
      ),
    )

    assertEquals(
      """
      ID        STATE        DONE  SIZE    SPEED     NAME
      3f2a9c1e  downloading  50%   1.0 KB  2.0 KB/s  a.iso
      9c1e3f2a  waiting      -     -       -         b.iso
      """.trimIndent(),
      formatTaskTable(tasks),
    )
  }

  @Test
  fun `JSON leaves out request headers`() {
    val task = FakeTask(
      "a",
      DownloadRequest(url = "https://x.test/a", headers = mapOf("Cookie" to "sid=1")),
      queued,
    )

    assertFalse("sid=1" in taskJson(task).toString())
  }

  private val queued = DownloadState.Queued

  private fun request(url: String, destination: Destination? = null) =
    DownloadRequest(url = url, destination = destination)
}
