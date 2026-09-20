package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertTrue

class TorrentListenerShutdownTest {
  @Test
  fun socketFailureDuringCanceledAcceptDoesNotEscapeEngineShutdown() = runTest {
    val entered = CompletableDeferred<Unit>()
    val closed = CompletableDeferred<Unit>()
    val network = object : TorrentNetwork {
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection = error("Unused")
      override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
      override suspend fun listen(local: PeerEndpoint): TorrentListener {
        if (local.host == "::") throw IOException("No second listener")
        return object : TorrentListener {
          override val local = PeerEndpoint("127.0.0.1", 12345)
          override suspend fun accept(): TorrentConnection {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
              // Socket providers can report closure instead of coroutine cancellation.
              throw IOException("Listener closed during accept")
            }
          }
          override fun close() { closed.complete(Unit) }
        }
      }
      override fun close() = Unit
    }
    val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false), rawNetwork = network)
    try {
      withContext(Dispatchers.Default) {
        withTimeout(5000) {
          engine.start()
          entered.await()
          engine.stop()
          assertTrue(closed.isCompleted)
        }
      }
    } finally { engine.stop() }
  }
}
