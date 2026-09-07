package com.linroid.ketch.torrent

import okio.FileSystem
import java.security.SecureRandom
import java.text.Normalizer

internal actual val torrentFileSystem: FileSystem = FileSystem.SYSTEM
private val secureRandom = SecureRandom()
internal actual fun torrentRandomBytes(size: Int): ByteArray =
  ByteArray(size).also { secureRandom.nextBytes(it) }

internal actual fun createTorrentNetwork(): TorrentNetwork = KtorTorrentNetwork()

internal actual fun canonicalTorrentName(value: String): String =
  Normalizer.normalize(value, Normalizer.Form.NFC)
