package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentEncryption
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.net.TorrentListener
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.peer.PeerOutcome
import com.linroid.ketch.torrent.peer.PeerOutcomeReport
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Incoming handshakes that fail in the engine's dispatcher, before naming their torrent. */
class TorrentIncomingDiagnosticsTest {
  private val source = PeerEndpoint("10.0.0.9", 51413)

  private fun connection(read: (Int) -> ByteArray) = object : TorrentConnection {
    override val remote = source
    override val utp = true
    override suspend fun readExactly(size: Int): ByteArray = read(size)
    override suspend fun write(bytes: ByteArray) = Unit
    override fun close() = Unit
  }

  private suspend fun dispatch(connection: TorrentConnection): List<PeerOutcomeReport> {
    val reports = mutableListOf<PeerOutcomeReport>()
    val engine = KotlinTorrentEngine(
      TorrentConfig(dhtEnabled = false, encryption = TorrentEncryption.DISABLED),
      rawNetwork = UnusedNetwork,
      peerOutcomes = { reports += it },
    )
    try {
      engine.dispatchIncoming(connection)
    } finally {
      engine.stop()
    }
    return reports
  }

  @Test
  fun closedBeforeTheHandshake_isReportedWithoutAWire() = runTest {
    val reports = dispatch(connection { throw IOException("Connection reset by peer") })
    val report = reports.single()
    assertEquals(source, report.endpoint)
    assertEquals(PeerOutcome.HANDSHAKE_CLOSED, report.outcome)
    assertTrue(report.incoming)
    assertTrue(report.utp)
    assertNull(report.mode)
  }

  private object UnusedNetwork : TorrentNetwork {
    override suspend fun connect(remote: PeerEndpoint): TorrentConnection = error("Unused")
    override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
    override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
    override fun close() = Unit
  }
}
