package com.linroid.ketch.app

import com.linroid.ketch.app.state.RejectedTracker
import com.linroid.ketch.app.state.addTrackers
import com.linroid.ketch.app.state.parseTrackers
import com.linroid.ketch.app.state.trackerHost
import com.linroid.ketch.app.state.trackerUrlError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
  fun `udp trackers need a port and no credentials`() {
    assertEquals("UDP trackers need a port.", trackerUrlError("udp://tracker.example/announce"))
    assertEquals(
      "UDP trackers can't include a user name or password.",
      trackerUrlError("udp://user@tracker.example:1337/announce"),
    )
  }

  @Test
  fun `other schemes and malformed hosts or ports are rejected`() {
    val schemeError = "Start it with http://, https:// or udp://."
    assertEquals(schemeError, trackerUrlError("tracker.example:1337/announce"))
    assertEquals(schemeError, trackerUrlError("wss://tracker.example/announce"))
    assertEquals("Missing the host name.", trackerUrlError("http:///announce"))
    assertEquals("Missing the host name.", trackerUrlError("udp://[2001:db8::1/announce"))
    for (port in listOf("0", "65536", "", "+80")) {
      assertEquals(
        "Use a port from 1 to 65535.",
        trackerUrlError("http://tracker.example:$port/announce"),
        port,
      )
    }
    assertNotNull(trackerUrlError("http://tracker example/announce"))
  }

  @Test
  fun `adding appends new trackers and returns the rejected ones in order`() {
    val current = listOf("udp://a.example:1337/announce")
    val added = addTrackers(
      current,
      "bad\nudp://a.example:1337/announce https://b.example/announce\n" +
        "udp://c.example/announce https://b.example/announce",
    )
    assertEquals(current + "https://b.example/announce", added.trackers)
    assertEquals(
      listOf(
        RejectedTracker("bad", "Start it with http://, https:// or udp://."),
        RejectedTracker("udp://c.example/announce", "UDP trackers need a port."),
      ),
      added.rejected,
    )
  }

  @Test
  fun `rows are titled by host falling back to the url`() {
    assertEquals("tracker.example", trackerHost("udp://user@tracker.example:1337/announce"))
    assertEquals("2001:db8::1", trackerHost("udp://[2001:db8::1]:6969"))
    assertEquals("udp://[2001:db8::1", trackerHost("udp://[2001:db8::1"))
  }
}
