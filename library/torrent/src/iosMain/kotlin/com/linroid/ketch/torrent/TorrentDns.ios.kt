package com.linroid.ketch.torrent

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun resolveTorrentHost(host: String): List<ByteArray> =
  withContext(Dispatchers.IO) {
    require(host.isNotEmpty() && host.length <= 253)
    memScoped {
      val hints = alloc<addrinfo>()
      hints.ai_family = AF_UNSPEC
      hints.ai_socktype = SOCK_DGRAM
      val result = alloc<CPointerVar<addrinfo>>()
      check(getaddrinfo(host, null, hints.ptr, result.ptr) == 0) { "Torrent DNS lookup failed" }
      val first = checkNotNull(result.value)
      try {
        val addresses = mutableListOf<ByteArray>()
        var current = result.value
        while (current != null && addresses.size < 16) {
          val item = current.pointed
          val address = item.ai_addr
          if (address != null) {
            when (item.ai_family) {
              AF_INET -> addresses += address.reinterpret<sockaddr_in>().pointed.sin_addr.ptr
                .reinterpret<ByteVar>().readBytes(4)
              AF_INET6 -> addresses += address.reinterpret<sockaddr_in6>().pointed.sin6_addr.ptr
                .reinterpret<ByteVar>().readBytes(16)
            }
          }
          current = item.ai_next
        }
        addresses
      } finally {
        freeaddrinfo(first)
      }
    }
  }
