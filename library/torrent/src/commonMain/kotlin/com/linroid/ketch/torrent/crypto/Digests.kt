package com.linroid.ketch.torrent.crypto

import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.core.digest.MessageDigest
import com.linroid.ketch.core.digest.newDigest

/**
 * A new SHA-1, which BitTorrent v1 identities and pieces use: the platform's where it has one,
 * several times faster than core's pure Kotlin one.
 */
internal fun newSha1(): MessageDigest = ChecksumAlgorithm.SHA_1.newDigest()

/** A new SHA-256, which BitTorrent v2 identities and Merkle trees use; as [newSha1]. */
internal fun newSha256(): MessageDigest = ChecksumAlgorithm.SHA_256.newDigest()

/** Returns the 20-byte SHA-1 digest of [data]. */
internal fun sha1Digest(data: ByteArray): ByteArray = com.linroid.ketch.core.digest.sha1Digest(data)

/** Returns the 32-byte SHA-256 digest of [data] without retaining the input. */
internal fun sha256Digest(data: ByteArray): ByteArray =
  com.linroid.ketch.core.digest.sha256Digest(data)
