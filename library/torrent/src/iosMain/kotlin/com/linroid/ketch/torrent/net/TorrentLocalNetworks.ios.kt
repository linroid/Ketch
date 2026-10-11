package com.linroid.ketch.torrent.net

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
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.sockaddr_in

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun localIpv4Networks(): List<LocalIpv4Network> =
  withContext(Dispatchers.IO) {
    memScoped {
      val list = alloc<CPointerVar<ifaddrs>>()
      if (getifaddrs(list.ptr) != 0) return@memScoped emptyList()
      val first = list.value
      try {
        val networks = mutableListOf<LocalIpv4Network>()
        var current = first
        while (current != null) {
          val item = current.pointed
          val address = item.ifa_addr
          val mask = item.ifa_netmask
          val flags = item.ifa_flags.toInt()
          if (address != null && mask != null && address.pointed.sa_family.toInt() == AF_INET &&
            flags and IFF_UP != 0 && flags and IFF_LOOPBACK == 0) {
            val bytes = address.reinterpret<sockaddr_in>().pointed.sin_addr.ptr
              .reinterpret<ByteVar>().readBytes(4)
            val maskBytes = mask.reinterpret<sockaddr_in>().pointed.sin_addr.ptr
              .reinterpret<ByteVar>().readBytes(4)
            val prefix = maskBytes.sumOf { (it.toInt() and 255).countOneBits() }
            if (privateIpv4(bytes) && prefix in 1..32) networks += LocalIpv4Network(bytes, prefix)
          }
          current = item.ifa_next
        }
        networks.distinct()
      } finally {
        freeifaddrs(first)
      }
    }
  }
