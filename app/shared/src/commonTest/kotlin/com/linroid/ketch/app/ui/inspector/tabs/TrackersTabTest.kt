package com.linroid.ketch.app.ui.inspector.tabs

import com.linroid.ketch.torrent.api.TorrentTracker
import com.linroid.ketch.torrent.api.TorrentTrackerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackersTabTest {
  @Test
  fun trackerView_working_listsWhatItNamedAndCounts() {
    val view = trackerView(
      TorrentTracker("udp://a.example:1337", 0, TorrentTrackerState.WORKING, peers = 12,
        seeders = 40, leechers = 7)
    )

    assertEquals(4, view.summary.size)
    assertEquals(TrackerTone.Good, view.tone)
    assertNull(view.message)
  }

  @Test
  fun trackerView_refused_saysWhyInTheBadTone() {
    val view = trackerView(
      TorrentTracker("http://b.example:80", 1, TorrentTrackerState.FAILED, failures = 2,
        message = "unregistered torrent")
    )

    assertEquals(1, view.summary.size)
    assertEquals(TrackerTone.Bad, view.tone)
    assertEquals("unregistered torrent", view.message)
    assertEquals(
      TrackerTone.Bad,
      trackerView(TorrentTracker("http://c.example:80", state = TorrentTrackerState.TIMED_OUT)).tone,
    )
  }

  @Test
  fun trackerView_notAskedYet_isNeutralWithoutCounts() {
    val view = trackerView(TorrentTracker("http://b.example:80", message = " "))

    assertEquals(1, view.summary.size)
    assertEquals(TrackerTone.Neutral, view.tone)
    assertNull(view.message)
  }
}
