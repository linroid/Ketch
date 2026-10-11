package com.linroid.ketch.torrent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

internal actual suspend fun localIpv4Networks(): List<LocalIpv4Network> =
  withContext(Dispatchers.IO) {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
      .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
      .flatMap { it.interfaceAddresses }
      .filter { it.address is Inet4Address && privateIpv4(it.address.address) }
      .map { LocalIpv4Network(it.address.address, it.networkPrefixLength.toInt().coerceIn(1, 32)) }
      .distinct()
  }
