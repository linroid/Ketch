package com.linroid.ketch.torrent.control

import com.linroid.ketch.torrent.MultiFileTorrent
import com.linroid.ketch.torrent.api.TorrentTracker
import com.linroid.ketch.torrent.api.TorrentTrackerState
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.MAX_TRACKERS
import com.linroid.ketch.torrent.tracker.TrackerScrape
import com.linroid.ketch.torrent.tracker.TrackerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** What a torrent's trackers list as, live and while it is not running. */
class TorrentTrackerViewsTest {
  private val maxBytes = 4 * 1024 * 1024

  @Test
  fun `live statuses list by tier with what the tracker said`() {
    val statuses = listOf(
      TrackerStatus(id = 1, outcome = TrackerStatus.Outcome.FAILED, consecutiveFailures = 3,
        failureReason = "unregistered torrent", label = "http://b.example:80", tier = 1),
      TrackerStatus(id = 0, outcome = TrackerStatus.Outcome.SUCCEEDED, lastPeerCount = 12,
        scrape = TrackerScrape(complete = 40, downloaded = 900, incomplete = 7),
        warning = "slow down", label = "udp://a.example:1337", tier = 0),
      TrackerStatus(id = 2, outcome = TrackerStatus.Outcome.TIMED_OUT,
        label = "http://c.example:80", tier = 1),
    )

    assertEquals(
      listOf(
        TorrentTracker("udp://a.example:1337", 0, TorrentTrackerState.WORKING, peers = 12,
          seeders = 40, leechers = 7, message = "slow down"),
        TorrentTracker("http://b.example:80", 1, TorrentTrackerState.FAILED, failures = 3,
          message = "unregistered torrent"),
        TorrentTracker("http://c.example:80", 1, TorrentTrackerState.TIMED_OUT),
      ),
      trackerViews(statuses),
    )
  }

  @Test
  fun `a torrent that is not running lists its announce list idle`() {
    val torrent = MultiFileTorrent()
    val info = checkNotNull(Bencode.parse(torrent.metainfo)["info"])
    val metainfo = Bencode.encode(mapOf(
      "announce" to "http://a.example/announce?passkey=secret",
      "announce-list" to listOf(
        listOf("http://a.example/announce?passkey=secret", "udp://b.example:1337"),
        listOf("wss://web.example/announce", "http://a.example/announce?passkey=secret"),
        listOf("https://c.example/announce"),
      ),
      "info" to info,
    ))

    val trackers = idleTrackers("torrent:x", metainfo, maxBytes)

    // The unusable tracker and the repeat are left out, and their tier with them.
    assertEquals(
      listOf(
        TorrentTracker("http://a.example:80", 0),
        TorrentTracker("udp://b.example:1337", 0),
        TorrentTracker("https://c.example:443", 1),
      ),
      trackers,
    )
  }

  @Test
  fun `a magnet without metadata lists the trackers it names`() {
    val url = "magnet:?xt=urn:btih:" + "a".repeat(40) +
      "&tr=http%3A%2F%2Fa.example%2Fannounce&tr=udp%3A%2F%2Fb.example%3A1337"

    assertEquals(
      listOf(TorrentTracker("http://a.example:80", 0), TorrentTracker("udp://b.example:1337", 1)),
      idleTrackers(url, null, maxBytes),
    )
    assertEquals(emptyList(), idleTrackers("magnet:?xt=urn:btih:" + "a".repeat(40), null, maxBytes))
    assertNull(idleTrackers("https://example.com/pack.torrent", null, maxBytes))
  }

  @Test
  fun `an edit removes trackers by endpoint and adds new ones as tiers of their own`() {
    val tiers = listOf(
      listOf("http://a.example/announce?passkey=secret", "udp://b.example:1337"),
      listOf("https://c.example/announce"),
    )

    val edited = editedTrackerTiers(tiers,
      add = listOf(" udp://d.example:6969 ", "https://c.example/announce", "",
        "udp://d.example:6969"),
      remove = listOf("http://a.example:80", "udp://missing.example:1"))

    assertEquals(
      listOf(
        listOf("udp://b.example:1337"),
        listOf("https://c.example/announce"),
        listOf("udp://d.example:6969"),
      ),
      edited,
    )
    // Removing a whole tier removes the tier.
    assertEquals(listOf(listOf("udp://b.example:1337")),
      editedTrackerTiers(edited, emptyList(),
        listOf("https://c.example:443", "udp://d.example:6969")))
    assertEquals(emptyList(), editedTrackerTiers(emptyList(), emptyList(), emptyList()))
  }

  @Test
  fun `an edit refuses trackers Ketch cannot announce to and more than a torrent may have`() {
    assertFailsWith<IllegalArgumentException> {
      editedTrackerTiers(emptyList(), listOf("wss://web.example/announce"), emptyList())
    }
    assertFailsWith<IllegalArgumentException> {
      editedTrackerTiers(emptyList(), listOf("not a url"), emptyList())
    }
    val many = (1..MAX_TRACKERS + 1).map { "udp://t$it.example:6969" }
    assertFailsWith<IllegalArgumentException> { editedTrackerTiers(emptyList(), many, emptyList()) }
    assertEquals(MAX_TRACKERS, editedTrackerTiers(emptyList(), many.dropLast(1), emptyList()).size)
  }
}
