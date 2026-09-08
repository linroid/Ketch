package com.linroid.ketch.torrent

/**
 * Creates the libtorrent4j-based [TorrentEngine].
 *
 * Called by the leaf `actual fun createTorrentEngine` in
 * `jvmMain` and `androidMain`.
 */
internal fun createLibtorrent4jEngine(
  config: TorrentConfig,
): TorrentEngine = Libtorrent4jEngine(config)
