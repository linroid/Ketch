package com.linroid.ketch.torrent

import okio.FileSystem
import java.security.SecureRandom

internal expect fun platformTorrentFileSystem(): FileSystem
internal actual val torrentFileSystem: FileSystem = platformTorrentFileSystem()
private val secureRandom = SecureRandom()
internal actual fun torrentRandomBytes(size: Int): ByteArray =
  ByteArray(size).also { secureRandom.nextBytes(it) }

internal actual fun createTorrentNetwork(): TorrentNetwork = KtorTorrentNetwork()
