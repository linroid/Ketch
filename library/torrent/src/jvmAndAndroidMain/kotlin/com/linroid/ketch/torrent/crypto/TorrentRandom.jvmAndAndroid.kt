package com.linroid.ketch.torrent.crypto

import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun torrentRandomBytes(size: Int): ByteArray =
  ByteArray(size).also { secureRandom.nextBytes(it) }
