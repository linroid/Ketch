package com.linroid.ketch.torrent.net

import com.linroid.ketch.torrent.TorrentDispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

internal actual suspend fun resolveTorrentHost(host: String): List<ByteArray> =
  withContext(TorrentDispatchers.lookup) {
    require(host.isNotEmpty() && host.length <= 253)
    InetAddress.getAllByName(host).take(16).map { it.address }
  }
