package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.CharacterCodingException

/** One receive loop, bounded correlated queries, and bounded responses to unsolicited traffic. */
internal class DhtRpc(
  private val socket: TorrentDatagramSocket,
  private val scope: CoroutineScope,
  private val onQuery: suspend (DhtMessage, PeerEndpoint) -> ByteArray?,
  private val nowMs: () -> Long = monotonicClock(),
) {
  private data class Key(val endpoint: PeerEndpoint, val transaction: ByteString)
  private val mutex = Mutex()
  private val pending = mutableMapOf<Key, CompletableDeferred<DhtMessage>>()
  private var receiver: Job? = null
  private var closed = false
  private val rate = DhtReplyQuota(nowMs)
  private val packets = DhtReplyQuota(nowMs, queryLimit = 1000, sourceLimit = 200)

  val local: PeerEndpoint get() = socket.local

  fun start() {
    check(receiver == null)
    receiver = scope.launch {
      try {
        while (true) {
          val packet = socket.receive()
          currentCoroutineContext().ensureActive()
          if (!packets.admitQuery(packet.remote.host)) continue
          val message = try { DhtCodec.parse(packet.bytes) } catch (_: IllegalArgumentException) {
            continue
          } catch (_: CharacterCodingException) {
            continue
          }
          if (message.type != "q") {
            mutex.withLock {
              pending[Key(packet.remote, message.transaction)]?.complete(message)
            }
          } else if (rate.admitQuery(packet.remote.host)) {
            val reply = try { onQuery(message, packet.remote) } catch (e: CancellationException) {
              throw e
            } catch (_: Exception) {
              DhtCodec.error(message.transaction, 203)
            }
            if (reply != null && reply.size <= DhtCodec.MAX_SEND &&
              rate.admitReply(packet.remote.host, reply.size)) socket.send(packet.remote, reply)
          }
        }
      } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        throw e
      } finally {
        withContext(NonCancellable) {
          mutex.withLock {
            closed = true
            pending.values.forEach { it.cancel() }
            pending.clear()
          }
        }
      }
    }
  }

  suspend fun query(
    endpoint: PeerEndpoint,
    name: String,
    arguments: Map<String, Any>,
    timeoutMs: Long = 5000,
  ): DhtMessage? {
    val numeric = requireNotNull(numericAddress(endpoint.host))
    val remote = PeerEndpoint(numericHost(numeric), endpoint.port)
    val transaction = torrentRandomBytes(8).toByteString()
    val key = Key(remote, transaction)
    val deferred = CompletableDeferred<DhtMessage>()
    mutex.withLock {
      check(!closed && receiver != null) { "DHT is not running" }
      check(pending.size < 64) { "Too many outstanding DHT queries" }
      check(key !in pending)
      pending[key] = deferred
    }
    try {
      socket.send(remote, DhtCodec.query(transaction, name, arguments))
      return withTimeoutOrNull(timeoutMs) { deferred.await() }
    } finally {
      withContext(NonCancellable) { mutex.withLock { pending.remove(key) } }
      deferred.cancel()
    }
  }

  suspend fun close() {
    receiver?.cancel()
    socket.close()
    withContext(NonCancellable) { receiver?.join() }
  }
}

/** Fixed one-second windows cap both CPU work and reflection traffic; source maps are bounded. */
internal class DhtReplyQuota(
  private val nowMs: () -> Long,
  private val queryLimit: Int = 100,
  private val sourceLimit: Int = 20,
) {
  private var window = -1L
  private var queries = 0
  private var bytes = 0
  private val sources = mutableMapOf<String, IntArray>()

  fun admitQuery(host: String): Boolean {
    rotate()
    if (queries >= queryLimit || host !in sources && sources.size >= 128) return false
    val source = sources.getOrPut(host) { IntArray(2) }
    if (source[0] >= sourceLimit) return false
    queries++
    source[0]++
    return true
  }

  fun admitReply(host: String, count: Int): Boolean {
    rotate()
    val source = sources[host] ?: return false
    if (count < 0 || count > 4096 - source[1] || count > 65536 - bytes) return false
    source[1] += count
    bytes += count
    return true
  }

  private fun rotate() {
    val current = nowMs() / 1000
    if (current != window) {
      window = current
      sources.clear()
      queries = 0
      bytes = 0
    }
  }
}
