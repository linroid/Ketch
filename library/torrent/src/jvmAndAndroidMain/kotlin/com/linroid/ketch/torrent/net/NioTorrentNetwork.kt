package com.linroid.ketch.torrent.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * TCP/UDP over non-blocking NIO channels whose readiness [selector] waits for on one thread.
 * Reads and writes run on the caller's dispatcher: a socket that is ready is read or written at
 * once, and only one that is not waits for the selector. So peers take no thread while they
 * wait, and none from the app's `Dispatchers.IO`, where ktor-network runs every socket's loop.
 * Higher layers own protocol deadlines and datagram validation.
 */
internal class NioTorrentNetwork(
  private val connectTimeoutMs: Long = 10_000,
  private val selector: NioSelector = NioSelector.shared,
) : TorrentNetwork {
  private val resources = TorrentResources()

  override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
    require(remote.port != 0)
    val address = InetSocketAddress(resolve(remote.host), remote.port)
    val channel = SocketChannel.open()
    val handle = selector.handle(channel)
    val lease = resources.register { handle.close() }
    try {
      channel.configureBlocking(false)
      channel.setOption(StandardSocketOptions.TCP_NODELAY, true)
      withTimeout(connectTimeoutMs) {
        if (!channel.connect(address)) {
          while (!channel.finishConnect()) handle.await(SelectionKey.OP_CONNECT)
        }
      }
      return Connection(channel, handle, lease)
    } catch (e: Throwable) {
      lease.close()
      throw e
    }
  }

  override suspend fun listen(local: PeerEndpoint): TorrentListener {
    val channel = ServerSocketChannel.open()
    val handle = selector.handle(channel)
    val lease = resources.register { handle.close() }
    try {
      channel.configureBlocking(false)
      channel.bind(InetSocketAddress(local.host, local.port), BACKLOG)
      val bound = endpoint(channel.localAddress as InetSocketAddress)
      return object : TorrentListener {
        override val local = bound

        override suspend fun accept(): TorrentConnection {
          var accepted = channel.accept()
          while (accepted == null) {
            handle.await(SelectionKey.OP_ACCEPT)
            accepted = channel.accept()
          }
          val acceptedHandle = selector.handle(accepted)
          val acceptedLease = resources.register { acceptedHandle.close() }
          try {
            accepted.configureBlocking(false)
            accepted.setOption(StandardSocketOptions.TCP_NODELAY, true)
            return Connection(accepted, acceptedHandle, acceptedLease)
          } catch (e: Throwable) {
            acceptedLease.close()
            throw e
          }
        }

        override fun close() = lease.close()
      }
    } catch (e: Throwable) {
      lease.close()
      throw e
    }
  }

  override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket {
    val channel = DatagramChannel.open()
    val handle = selector.handle(channel)
    val lease = resources.register { handle.close() }
    try {
      channel.configureBlocking(false)
      channel.bind(InetSocketAddress(local.host, local.port))
      val bound = endpoint(channel.localAddress as InetSocketAddress)
      // JVM dual-stack UDP may report :: even when the requested wildcard is IPv4.
      val logicalLocal = if (local.host == "0.0.0.0") bound.copy(host = "0.0.0.0") else bound
      return Datagrams(channel, handle, lease, logicalLocal)
    } catch (e: Throwable) {
      lease.close()
      throw e
    }
  }

  override fun close() {
    resources.close()
  }

  private class Connection(
    private val channel: SocketChannel,
    private val handle: NioSelector.Handle,
    private val lease: TorrentResources.Lease,
  ) : TorrentConnection {
    override val remote = endpoint(channel.remoteAddress as InetSocketAddress)
    private val input: ByteBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES).apply { limit(0) }
    private val reading = Mutex()
    private val writing = Mutex()

    override suspend fun readExactly(size: Int): ByteArray {
      require(size in 0..16 * 1024 * 1024)
      val bytes = ByteArray(size)
      reading.withLock {
        var filled = 0
        while (filled < size) {
          if (input.hasRemaining()) {
            val count = minOf(input.remaining(), size - filled)
            input.get(bytes, filled, count)
            filled += count
            continue
          }
          val missing = size - filled
          // Large reads go straight into the result; small ones refill the buffer, so a run of
          // short messages costs one system call rather than one each.
          val read = if (missing >= input.capacity()) {
            channel.read(ByteBuffer.wrap(bytes, filled, missing)).also { if (it > 0) filled += it }
          } else {
            input.clear()
            channel.read(input).also { input.flip() }
          }
          if (read < 0) throw EOFException("The peer closed the connection")
          if (read == 0) handle.await(SelectionKey.OP_READ)
        }
      }
      return bytes
    }

    override suspend fun write(bytes: ByteArray) {
      writing.withLock {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
          if (channel.write(buffer) == 0) handle.await(SelectionKey.OP_WRITE)
        }
      }
    }

    override fun close() = lease.close()
  }

  private class Datagrams(
    private val channel: DatagramChannel,
    private val handle: NioSelector.Handle,
    private val lease: TorrentResources.Lease,
    override val local: PeerEndpoint,
  ) : TorrentDatagramSocket {
    private val input = ByteBuffer.allocateDirect(MAX_DATAGRAM_BYTES)
    private val receiving = Mutex()
    private val sending = Mutex()

    override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) {
      require(bytes.size <= 65507 && remote.port != 0)
      val target = InetSocketAddress(resolve(remote.host), remote.port)
      sending.withLock {
        val buffer = ByteBuffer.wrap(bytes)
        // A full send buffer accepts nothing, rather than part of the datagram.
        while (channel.send(buffer, target) == 0) handle.await(SelectionKey.OP_WRITE)
      }
    }

    override suspend fun receive(): TorrentDatagram = receiving.withLock { awaitDatagram() }

    private suspend fun awaitDatagram(): TorrentDatagram {
      while (true) {
        input.clear()
        val sender = channel.receive(input) as InetSocketAddress?
        if (sender != null) {
          input.flip()
          val bytes = ByteArray(input.remaining()).also { input.get(it) }
          return TorrentDatagram(endpoint(sender), bytes)
        }
        handle.await(SelectionKey.OP_READ)
      }
    }

    override fun close() = lease.close()
  }

  private companion object {
    /** As ktor-network's listeners, which this replaces. */
    const val BACKLOG = 511
    const val READ_BUFFER_BYTES = 16 * 1024
    const val MAX_DATAGRAM_BYTES = 65536

    fun endpoint(address: InetSocketAddress): PeerEndpoint =
      PeerEndpoint(numericHost(address.address.address), address.port)

    /** Numeric hosts need no lookup; names, such as a DHT router's, are resolved off-thread. */
    suspend fun resolve(host: String): InetAddress {
      numericAddress(host)?.let { return InetAddress.getByAddress(it) }
      val addresses = resolveTorrentHost(host)
      return InetAddress.getByAddress(host, addresses.firstOrNull()
        ?: throw UnknownHostException("Could not resolve a torrent host"))
    }
  }
}
