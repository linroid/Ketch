package com.linroid.ketch.torrent

/** Core's pure Kotlin SHA-1, which BitTorrent v1 identities and pieces use. */
internal typealias Sha1 = com.linroid.ketch.core.digest.Sha1

/** Core's pure Kotlin SHA-256, which BitTorrent v2 identities and Merkle trees use. */
internal typealias Sha256 = com.linroid.ketch.core.digest.Sha256

/** Returns the 20-byte SHA-1 digest of [data]. */
internal fun sha1Digest(data: ByteArray): ByteArray = com.linroid.ketch.core.digest.sha1Digest(data)

/** Returns the 32-byte SHA-256 digest of [data] without retaining the input. */
internal fun sha256Digest(data: ByteArray): ByteArray =
  com.linroid.ketch.core.digest.sha256Digest(data)
