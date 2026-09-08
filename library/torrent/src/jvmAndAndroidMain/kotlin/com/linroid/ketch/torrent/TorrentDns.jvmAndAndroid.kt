package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

internal actual suspend fun resolveTorrentHost(host: String): List<ByteArray> =
  withContext(Dispatchers.IO) {
    require(host.isNotEmpty() && host.length <= 253)
    InetAddress.getAllByName(host).take(16).map { it.address }
  }
