package com.linroid.ketch.app

import com.linroid.ketch.app.state.isTrackerUrl
import com.linroid.ketch.app.state.parseTrackers
import com.linroid.ketch.app.state.trackersError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerUrlsTest {

  @Test
  fun `lines are trimmed and blank or repeated ones dropped`() {
    assertEquals(
      listOf("udp://a.example:1337/announce", "https://b.example/announce"),
      parseTrackers(
        "  udp://a.example:1337/announce \n\n" +
          "https://b.example/announce\r\nudp://a.example:1337/announce\n",
      ),
    )
  }

  @Test
  fun `http https and udp trackers with a host are accepted`() {
    assertTrue(isTrackerUrl("http://tracker.example/announce"))
    assertTrue(isTrackerUrl("HTTPS://tracker.example:8443/announce?passkey=abc"))
    assertTrue(isTrackerUrl("udp://tracker.example:1337/announce"))
    assertTrue(isTrackerUrl("udp://[2001:db8::1]:6969"))
    assertTrue(isTrackerUrl("http://user:secret@tracker.example/announce"))
  }

  @Test
  fun `udp trackers need a port and no credentials`() {
    assertFalse(isTrackerUrl("udp://tracker.example/announce"))
    assertFalse(isTrackerUrl("udp://user@tracker.example:1337/announce"))
  }

  @Test
  fun `other schemes and malformed hosts or ports are rejected`() {
    assertFalse(isTrackerUrl("tracker.example:1337/announce"))
    assertFalse(isTrackerUrl("wss://tracker.example/announce"))
    assertFalse(isTrackerUrl("http:///announce"))
    assertFalse(isTrackerUrl("http://tracker.example:0/announce"))
    assertFalse(isTrackerUrl("http://tracker.example:65536/announce"))
    assertFalse(isTrackerUrl("http://tracker.example:/announce"))
    assertFalse(isTrackerUrl("http://tracker.example:+80/announce"))
    assertFalse(isTrackerUrl("udp://[2001:db8::1/announce"))
    assertFalse(isTrackerUrl("http://tracker example/announce"))
  }

  @Test
  fun `the error names the invalid line or counts several`() {
    assertNull(trackersError(""))
    assertNull(trackersError("udp://a.example:1337\nhttps://b.example/announce"))
    assertEquals(
      "Not a tracker URL: udp://a.example",
      trackersError("https://b.example/announce\nudp://a.example"),
    )
    assertEquals(
      "2 lines aren't tracker URLs, including foo",
      trackersError("foo\nhttps://b.example/announce\nbar"),
    )
  }
}
