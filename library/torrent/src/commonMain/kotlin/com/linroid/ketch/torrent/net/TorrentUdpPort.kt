package com.linroid.ketch.torrent.net

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.torrent.describeWithoutUrls
import com.linroid.ketch.torrent.utp.UtpPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * One UDP socket shared by DHT and uTP, as BitTorrent clients share their listen port: every
 * datagram that [UtpPacket.looksLikeUtp] goes to [utp], the rest (bencoded DHT messages) to the
 * view [openDht] returned last. Closing that view leaves the port open; [close] ends both.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentUdpPort(
  private val socket: TorrentDatagramSocket,
  scope: CoroutineScope,
  /** Handles one uTP datagram; null drops them, as with uTP switched off. */
  private val utp: (suspend (TorrentDatagram) -> Unit)?,
  /** Runs once the socket stopped receiving on its own, not when [close] stopped it. */
  private val onStopped: () -> Unit = {},
) {
  private val log = KetchLogger("TorrentEngine")
  private val dhtPackets = AtomicReference<Channel<TorrentDatagram>?>(null)
  private val closed = AtomicBoolean(false)
  private val receiver: Job = scope.launch {
    var failures = 0
    try {
      while (true) {
        val datagram = try {
          socket.receive().also { failures = 0 }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Windows reports an ICMP error for an earlier send on the next receive; the socket
          // still works. Only a socket closed, or failing again and again, stops the port.
          if (closed.load() || ++failures > MAX_RECEIVE_FAILURES) throw e
          log.d { "UDP port ${socket.local} receive failed: ${e.describeWithoutUrls()}" }
          delay(10)
          continue
        }
        if (UtpPacket.looksLikeUtp(datagram.bytes)) {
          utp?.let { handle ->
            try {
              handle(datagram)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              // One bad packet or peer never stops the port.
              log.v { "uTP datagram from ${datagram.remote} failed: ${e.describeWithoutUrls()}" }
            }
          }
        } else {
          dhtPackets.load()?.trySend(datagram)
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.d { "UDP port ${socket.local} stopped: ${e.describeWithoutUrls()}" }
      if (!closed.load()) onStopped()
    } finally {
      dhtPackets.exchange(null)?.close(IOException("UDP port closed"))
      socket.close()
    }
  }

  val local: PeerEndpoint get() = socket.local

  val isRunning: Boolean get() = receiver.isActive

  suspend fun send(remote: PeerEndpoint, bytes: ByteArray) = socket.send(remote, bytes)

  /**
   * DHT's side of the port, replacing any earlier view: it receives what is not uTP, and
   * closing it keeps the port.
   */
  fun openDht(): TorrentDatagramSocket {
    val packets = Channel<TorrentDatagram>(256, BufferOverflow.DROP_OLDEST)
    dhtPackets.exchange(packets)?.close(IOException("DHT view replaced"))
    if (!receiver.isActive) packets.close(IOException("UDP port closed"))
    return object : TorrentDatagramSocket {
      override val local: PeerEndpoint get() = socket.local
      override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) =
        socket.send(remote, bytes)
      override suspend fun receive(): TorrentDatagram = packets.receive()
      override fun close() {
        dhtPackets.compareAndSet(packets, null)
        packets.close(IOException("DHT view closed"))
      }
    }
  }

  fun close() {
    closed.store(true)
    receiver.cancel()
    socket.close()
  }

  private companion object {
    /** Receive errors in a row before the port gives up as broken. */
    const val MAX_RECEIVE_FAILURES = 64
  }
}
