package com.linroid.ketch.torrent

internal actual fun createTorrentEngine(
  config: TorrentConfig,
): TorrentEngine {
  NativeLibraryLoader.ensureLoaded()
  return createLibtorrent4jEngine(config)
}

internal actual fun encodeBase64(data: ByteArray): String =
  jvmEncodeBase64(data)

internal actual fun decodeBase64(data: String): ByteArray =
  jvmDecodeBase64(data)

internal actual fun sha1Digest(data: ByteArray): ByteArray =
  jvmSha1Digest(data)
