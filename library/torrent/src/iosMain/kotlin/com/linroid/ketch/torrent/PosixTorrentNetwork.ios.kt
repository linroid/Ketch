@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.linroid.ketch.torrent

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.SO_ERROR
import platform.posix.SO_NOSIGPIPE
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.accept
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getpeername
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Nonblocking Darwin sockets. Keeps full sockaddr bytes, including IPv6 addresses/scope.
 * Ktor 3.5.2 omits sin6_addr when packing native addresses; use OS I/O until that is fixed.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PosixTorrentNetwork : TorrentNetwork {
  private val resources = TorrentResources()

  private inner class Handle(val fd: Int) {
    val closed = AtomicBoolean(false)
    private val operations = AtomicInt(0)
    private val disposed = AtomicBoolean(false)
    private val lease = resources.register {
      closed.store(true)
      if (operations.load() == 0) dispose()
    }
    fun close() = lease.close()
    fun checkOpen() = check(!closed.load()) { "Torrent socket closed" }

    // Defer descriptor recycling until any in-flight nonblocking syscall returns.
    fun <T> operation(block: () -> T): T {
      operations.fetchAndAdd(1)
      try {
        checkOpen()
        return block()
      } finally {
        if (operations.fetchAndAdd(-1) == 1 && closed.load()) dispose()
      }
    }

    private fun dispose() {
      if (disposed.compareAndSet(false, true)) close(fd)
    }
  }

  private data class Address(val bytes: ByteArray, val family: Int) {
    fun <T> use(block: (CPointer<sockaddr>, UInt) -> T): T = bytes.usePinned {
      block(it.addressOf(0).reinterpret(), bytes.size.toUInt())
    }
  }

  private suspend fun resolve(endpoint: PeerEndpoint, type: Int): Address =
    withContext(Dispatchers.IO) {
      memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = type
        val result = alloc<CPointerVar<addrinfo>>()
        check(getaddrinfo(endpoint.host, endpoint.port.toString(), hints.ptr, result.ptr) == 0) {
          "Cannot resolve torrent endpoint"
        }
        val first = checkNotNull(result.value)
        try {
          val info = first.pointed
          val bytes = info.ai_addr!!.reinterpret<ByteVar>().readBytes(info.ai_addrlen.toInt())
          Address(bytes, info.ai_family)
        } finally {
          freeaddrinfo(first)
        }
      }
    }

  private fun create(family: Int, type: Int): Handle {
    val fd = socket(family, type, 0)
    check(fd >= 0) { "Cannot create torrent socket: $errno" }
    return configure(fd)
  }

  private fun configure(fd: Int): Handle {
    try {
      check(fcntl(fd, F_SETFL, O_NONBLOCK) == 0) { "Cannot set nonblocking socket" }
      memScoped {
        val enabled = alloc<IntVar>()
        enabled.value = 1
        check(setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, sizeOf<IntVar>().toUInt()) == 0)
      }
    } catch (e: Throwable) {
      close(fd)
      throw e
    }
    return Handle(fd)
  }

  override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
    require(remote.port > 0)
    val address = resolve(remote, SOCK_STREAM)
    val handle = create(address.family, SOCK_STREAM)
    try {
      withTimeout(10_000) {
        val status = address.use { pointer, size ->
          handle.operation { connect(handle.fd, pointer, size) }
        }
        if (status != 0) {
          check(errno == EINPROGRESS || errno == EINTR) { "Torrent connect failed: $errno" }
          while (true) {
            handle.checkOpen()
            val ready = memScoped {
              val descriptor = alloc<pollfd>()
              descriptor.fd = handle.fd
              descriptor.events = POLLOUT.toShort()
              handle.operation { poll(descriptor.ptr, 1u, 0) }
            }
            if (ready > 0) break
            delay(5)
          }
          memScoped {
            val error = alloc<IntVar>()
            val size = alloc<socklen_tVar>()
            size.value = sizeOf<IntVar>().toUInt()
            val result = handle.operation {
              getsockopt(handle.fd, SOL_SOCKET, SO_ERROR, error.ptr, size.ptr)
            }
            check(result == 0)
            check(error.value == 0) { "Torrent connect failed: ${error.value}" }
          }
        }
      }
      return connection(handle, remote)
    } catch (e: Throwable) {
      handle.close()
      throw e
    }
  }

  override suspend fun listen(local: PeerEndpoint): TorrentListener {
    val address = resolve(local, SOCK_STREAM)
    val handle = create(address.family, SOCK_STREAM)
    try {
      val bound = address.use { pointer, size ->
        handle.operation { bind(handle.fd, pointer, size) }
      }
      check(bound == 0)
      check(handle.operation { listen(handle.fd, 64) } == 0)
      return object : TorrentListener {
        override val local = localEndpoint(handle)
        override suspend fun accept(): TorrentConnection {
          while (true) {
            handle.checkOpen()
            currentCoroutineContext().ensureActive()
            val fd = handle.operation { accept(handle.fd, null, null) }
            if (fd >= 0) {
              val accepted = configure(fd)
              try {
                val remote = memScoped {
                  val storage = alloc<sockaddr_storage>()
                  val size = alloc<socklen_tVar>()
                  size.value = sizeOf<sockaddr_storage>().toUInt()
                  val result = accepted.operation {
                    getpeername(fd, storage.ptr.reinterpret(), size.ptr)
                  }
                  check(result == 0)
                  endpoint(storage.ptr.reinterpret())
                }
                return connection(accepted, remote)
              } catch (e: Throwable) {
                accepted.close()
                throw e
              }
            }
            retry()
          }
        }
        override fun close() = handle.close()
      }
    } catch (e: Throwable) {
      handle.close()
      throw e
    }
  }

  override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket {
    val address = resolve(local, SOCK_DGRAM)
    val handle = create(address.family, SOCK_DGRAM)
    try {
      val bound = address.use { pointer, size ->
        handle.operation { bind(handle.fd, pointer, size) }
      }
      check(bound == 0)
      return object : TorrentDatagramSocket {
        override val local = localEndpoint(handle)
        override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) {
          require(bytes.isNotEmpty() && bytes.size <= 65507 && remote.port > 0)
          val target = resolve(remote, SOCK_DGRAM)
          while (true) {
            handle.checkOpen()
            currentCoroutineContext().ensureActive()
            val written = bytes.usePinned { data ->
              target.use { pointer, size ->
                handle.operation {
                  sendto(handle.fd, data.addressOf(0), bytes.size.toULong(), 0, pointer, size)
                }
              }
            }
            if (written >= 0) {
              check(written.toInt() == bytes.size) { "Short datagram write" }
              return
            }
            retry()
          }
        }
        override suspend fun receive(): TorrentDatagram {
          val bytes = ByteArray(65536)
          while (true) {
            handle.checkOpen()
            currentCoroutineContext().ensureActive()
            val result = memScoped {
              val storage = alloc<sockaddr_storage>()
              val size = alloc<socklen_tVar>()
              size.value = sizeOf<sockaddr_storage>().toUInt()
              val count = bytes.usePinned {
                handle.operation {
                  recvfrom(handle.fd, it.addressOf(0), bytes.size.toULong(), 0,
                    storage.ptr.reinterpret(), size.ptr)
                }
              }
              if (count < 0) null else TorrentDatagram(
                endpoint(storage.ptr.reinterpret()), bytes.copyOf(count.toInt())
              )
            }
            if (result != null) return result
            retry()
          }
        }
        override fun close() = handle.close()
      }
    } catch (e: Throwable) {
      handle.close()
      throw e
    }
  }

  private fun connection(handle: Handle, endpoint: PeerEndpoint): TorrentConnection =
    object : TorrentConnection {
      override val remote = endpoint
      override suspend fun readExactly(size: Int): ByteArray {
        require(size in 0..16 * 1024 * 1024)
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
          handle.checkOpen()
          currentCoroutineContext().ensureActive()
          val count = bytes.usePinned {
            handle.operation { recv(handle.fd, it.addressOf(offset), (size - offset).toULong(), 0) }
          }
          check(count != 0L) { "Torrent peer disconnected" }
          if (count < 0) retry() else offset += count.toInt()
        }
        return bytes
      }
      override suspend fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
          handle.checkOpen()
          currentCoroutineContext().ensureActive()
          val count = bytes.usePinned {
            handle.operation {
              send(handle.fd, it.addressOf(offset), (bytes.size - offset).toULong(), 0)
            }
          }
          if (count < 0) retry() else {
            check(count > 0) { "Short torrent socket write" }
            offset += count.toInt()
          }
        }
      }
      override fun close() = handle.close()
    }

  private suspend fun retry() {
    val error = errno
    check(error == EAGAIN || error == EWOULDBLOCK || error == EINTR) {
      "Torrent socket I/O failed: $error"
    }
    delay(5)
  }

  private fun localEndpoint(handle: Handle): PeerEndpoint = memScoped {
    val storage = alloc<sockaddr_storage>()
    val size = alloc<socklen_tVar>()
    size.value = sizeOf<sockaddr_storage>().toUInt()
    check(handle.operation { getsockname(handle.fd, storage.ptr.reinterpret(), size.ptr) } == 0)
    endpoint(storage.ptr.reinterpret())
  }

  private fun endpoint(address: CPointer<sockaddr>): PeerEndpoint {
    val header = address.reinterpret<ByteVar>().readBytes(4)
    val port = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
    val host = when (address.pointed.sa_family.toInt()) {
      AF_INET -> numericHost(
        address.reinterpret<sockaddr_in>().pointed.sin_addr.ptr.reinterpret<ByteVar>().readBytes(4)
      )
      AF_INET6 -> {
        val value = address.reinterpret<sockaddr_in6>().pointed
        val host = numericHost(value.sin6_addr.ptr.reinterpret<ByteVar>().readBytes(16))
        if (value.sin6_scope_id == 0u) host else "$host%${value.sin6_scope_id}"
      }
      else -> error("Unsupported address family")
    }
    return PeerEndpoint(host, port)
  }

  override fun close() = resources.close()
}
