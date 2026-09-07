package com.linroid.ketch.torrent

/** Resolves all available address families; protocol parsers themselves never perform DNS. */
internal expect suspend fun resolveTorrentHost(host: String): List<ByteArray>
