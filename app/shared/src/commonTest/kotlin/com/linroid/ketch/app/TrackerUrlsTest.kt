package com.linroid.ketch.app

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.instance.TrackerListStatus
import com.linroid.ketch.app.state.addTrackers
import com.linroid.ketch.app.state.isTrackerListUrl
import com.linroid.ketch.app.state.parseTrackers
import com.linroid.ketch.app.state.trackerHost
import com.linroid.ketch.app.state.trackerListStatusText
import com.linroid.ketch.app.state.unusedListedTrackers
import com.linroid.ketch.app.state.trackerUrlError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TrackerUrlsTest {

  @Test
  fun `pasted lists split on new lines and spaces without repeats`() {
    assertEquals(
      listOf("udp://a.example:1337/announce", "https://b.example/announce"),
      parseTrackers(
        "  udp://a.example:1337/announce \n\n" +
          "https://b.example/announce\r\nudp://a.example:1337/announce\t",
      ),
    )
  }

  @Test
  fun `http https and udp trackers with a host are accepted`() {
    assertNull(trackerUrlError("http://tracker.example/announce"))
    assertNull(trackerUrlError("HTTPS://tracker.example:8443/announce?passkey=abc"))
    assertNull(trackerUrlError("udp://tracker.example:1337/announce"))
    assertNull(trackerUrlError("udp://[2001:db8::1]:6969"))
    assertNull(trackerUrlError("http://user:secret@tracker.example/announce"))
  }

  @Test
  fun `udp trackers need a port and no credentials`() = runTest {
    assertEquals(
      "UDP trackers need a port.",
      trackerUrlError("udp://tracker.example/announce").load(),
    )
    assertEquals(
      "UDP trackers can't include a user name or password.",
      trackerUrlError("udp://user@tracker.example:1337/announce").load(),
    )
  }

  @Test
  fun `other schemes and malformed hosts or ports are rejected`() = runTest {
    val schemeError = "Start it with http://, https:// or udp://."
    assertEquals(schemeError, trackerUrlError("tracker.example:1337/announce").load())
    assertEquals(schemeError, trackerUrlError("wss://tracker.example/announce").load())
    assertEquals("Missing the host name.", trackerUrlError("http:///announce").load())
    assertEquals("Missing the host name.", trackerUrlError("udp://[2001:db8::1/announce").load())
    for (port in listOf("0", "65536", "", "+80")) {
      assertEquals(
        "Use a port from 1 to 65535.",
        trackerUrlError("http://tracker.example:$port/announce").load(),
        port,
      )
    }
    assertNotNull(trackerUrlError("http://tracker example/announce"))
  }

  @Test
  fun `adding appends new trackers and returns the rejected ones in order`() = runTest {
    val current = listOf("udp://a.example:1337/announce")
    val added = addTrackers(
      current,
      "bad\nudp://a.example:1337/announce https://b.example/announce\n" +
        "udp://c.example/announce https://b.example/announce",
    )
    assertEquals(current + "https://b.example/announce", added.trackers)
    assertEquals(
      listOf(
        "bad" to "Start it with http://, https:// or udp://.",
        "udp://c.example/announce" to "UDP trackers need a port.",
      ),
      added.rejected.map { it.url to it.problem.load() },
    )
  }

  @Test
  fun `rows are titled by host falling back to the url`() {
    assertEquals("tracker.example", trackerHost("udp://user@tracker.example:1337/announce"))
    assertEquals("2001:db8::1", trackerHost("udp://[2001:db8::1]:6969"))
    assertEquals("udp://[2001:db8::1", trackerHost("udp://[2001:db8::1"))
  }

  @Test
  fun `tracker list addresses are http or https urls with a host`() {
    assertTrue(isTrackerListUrl("https://raw.githubusercontent.com/ngosang/trackerslist/x.txt"))
    assertTrue(isTrackerListUrl("HTTP://lists.example:8080/best.txt"))
    assertFalse(isTrackerListUrl("udp://tracker.example:1337/announce"))
    assertFalse(isTrackerListUrl("https:///best.txt"))
    assertFalse(isTrackerListUrl("https://lists.example/best list.txt"))
    assertFalse(isTrackerListUrl("lists.example/best.txt"))
  }

  @Test
  fun `tracker list status says what the list holds and how old it is`() = runTest {
    val now = Instant.fromEpochMilliseconds(1_790_000_000_000)
    val updated = now - 3.hours - 12.minutes
    val twenty = List(20) { "udp://tracker$it.example:6969/announce" }
    assertEquals(
      "20 trackers · updated 3h 12m ago",
      trackerListStatusText(TrackerListStatus(twenty, updated), now).load(),
    )
    assertEquals(
      "Couldn't update the list. Using 1 tracker from 3h 12m ago.",
      trackerListStatusText(TrackerListStatus(twenty.take(1), updated, failed = true), now).load(),
    )
    assertEquals(
      "Using 20 built-in trackers until the list downloads.",
      trackerListStatusText(TrackerListStatus(twenty), now).load(),
    )
    assertEquals(
      "Couldn't download the list. Using 20 built-in trackers.",
      trackerListStatusText(TrackerListStatus(twenty, failed = true), now).load(),
    )
    assertEquals(
      "Couldn't download the list.",
      trackerListStatusText(TrackerListStatus(failed = true), now).load(),
    )
    assertEquals(
      "Downloading the list…",
      trackerListStatusText(TrackerListStatus(twenty, updated, updating = true), now).load(),
    )
  }

  @Test
  fun `list trackers past the 64 the engine uses are unused with extra trackers counted first`() {
    val extra = List(60) { "udp://extra$it.example:6969/announce" }
    val listed = listOf(extra[0]) + List(6) { "udp://listed$it.example:6969/announce" }
    assertEquals(
      setOf("udp://listed4.example:6969/announce", "udp://listed5.example:6969/announce"),
      unusedListedTrackers(extra, listed),
    )
    assertEquals(emptySet(), unusedListedTrackers(emptyList(), listed))
  }
}
