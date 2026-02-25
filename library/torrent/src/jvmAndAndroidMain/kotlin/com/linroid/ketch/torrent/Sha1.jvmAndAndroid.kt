package com.linroid.ketch.torrent

import java.security.MessageDigest

/**
 * SHA-1 digest using `java.security.MessageDigest`.
 *
 * Called by the leaf `actual fun sha1Digest` in `jvmMain` and
 * `androidMain`.
 */
internal fun jvmSha1Digest(data: ByteArray): ByteArray {
  return MessageDigest.getInstance("SHA-1").digest(data)
}
