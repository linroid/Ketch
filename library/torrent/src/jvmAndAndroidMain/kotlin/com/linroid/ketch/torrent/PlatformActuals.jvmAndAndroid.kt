package com.linroid.ketch.torrent

import java.util.Base64

/**
 * Creates the libtorrent4j-based [TorrentEngine].
 *
 * Called by the leaf `actual fun createTorrentEngine` in
 * `jvmMain` and `androidMain`.
 */
internal fun createLibtorrent4jEngine(
  config: TorrentConfig,
): TorrentEngine = Libtorrent4jEngine(config)

internal fun jvmEncodeBase64(data: ByteArray): String {
  return Base64.getEncoder().encodeToString(data)
}

internal fun jvmDecodeBase64(data: String): ByteArray {
  return Base64.getDecoder().decode(data)
}
