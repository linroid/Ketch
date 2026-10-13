package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.torrent.api.TorrentReachability
import com.linroid.ketch.torrent.api.TorrentReachabilityStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TorrentReachabilityNoticeTest {
  private suspend fun notice(reachability: TorrentReachability) =
    reachabilityNotice(reachability, "NAS")?.let { (text, tone) -> text.load() to tone }

  @Test
  fun eachStatus_saysWhetherPeersCanConnect() = runTest {
    assertEquals(
      "Peers can connect to NAS on port 6881." to NoticeTone.Success,
      notice(TorrentReachability(TorrentReachabilityStatus.REACHABLE, port = 6881)),
    )
    assertEquals(
      "Port 6881 is forwarded to NAS through 2 routers, so peers can connect." to
        NoticeTone.Success,
      notice(TorrentReachability(TorrentReachabilityStatus.FORWARDED, port = 6881,
        forwardedLayers = 2)),
    )
    assertEquals(
      "Peers can't connect to NAS: its router forwards port 6881, but a network outside it, " +
        "often your provider's, doesn't. Downloads still work, and hole punching reaches " +
        "peers behind routers." to NoticeTone.Warning,
      notice(TorrentReachability(TorrentReachabilityStatus.BLOCKED, port = 6881,
        forwardedLayers = 1, holePunching = true)),
    )
    assertEquals(
      "No router forwards port 6881 to NAS, so peers may not be able to connect. Turn on Port " +
        "mapping, or forward the port on your router. Downloads still work. Turn on uTP so " +
        "hole punching can reach peers behind routers." to NoticeTone.Info,
      notice(TorrentReachability(TorrentReachabilityStatus.NOT_FORWARDED, port = 6881)),
    )
  }

  @Test
  fun unknownOrWithoutAPort_showsNothing() = runTest {
    assertNull(notice(TorrentReachability()))
    assertNull(notice(TorrentReachability(TorrentReachabilityStatus.BLOCKED)))
  }
}
