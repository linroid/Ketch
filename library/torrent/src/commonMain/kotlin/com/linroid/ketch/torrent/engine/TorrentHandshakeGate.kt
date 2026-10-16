package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.net.numericAddress
import com.linroid.ketch.torrent.net.numericHost
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Incoming connections still in their handshake, which hold a slot of the connection budget
 * before they name a torrent: at most [limit] at once and [perHost] from one host (an IPv6
 * host's /64, which one subscriber usually holds whole). Peers that open connections and send
 * nothing can then never fill the budget our own dials and finished handshakes need.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentHandshakeGate(
  private val limit: () -> Int,
  private val perHost: Int = 4,
) {
  private val pending = AtomicReference(emptyMap<String?, Int>())

  /** Admits a handshake from [host]; false refuses it. Each admitted one must [leave]. */
  fun enter(host: String): Boolean {
    val key = handshakeHostKey(host)
    while (true) {
      val current = pending.load()
      val fromHost = current[key] ?: 0
      if (key != null && fromHost >= perHost || current.values.sum() >= limit()) return false
      if (pending.compareAndSet(current, current + (key to fromHost + 1))) return true
    }
  }

  fun leave(host: String) {
    val key = handshakeHostKey(host)
    while (true) {
      val current = pending.load()
      val fromHost = current[key] ?: return
      val next = if (fromHost <= 1) current - key else current + (key to fromHost - 1)
      if (pending.compareAndSet(current, next)) return
    }
  }
}

/**
 * What [TorrentHandshakeGate] counts a host under: IPv4 as it is, IPv6 by its first 64 bits, and
 * `null` for loopback, which only our own processes reach and the per-host limit leaves alone.
 */
internal fun handshakeHostKey(host: String): String? {
  val bytes = numericAddress(host.substringBefore('%')) ?: return host
  val ipv4 = numericHost(bytes)
  if (':' !in ipv4) return if (ipv4.startsWith("127.")) null else ipv4
  if (bytes.indices.all { bytes[it] == (if (it == 15) 1 else 0).toByte() }) return null
  return numericHost(ByteArray(16) { if (it < 8) bytes[it] else 0 }) + "/64"
}
