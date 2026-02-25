package com.linroid.ketch.torrent

/** Returns the 20-byte SHA-1 digest of [data]. */
internal expect fun sha1Digest(data: ByteArray): ByteArray
