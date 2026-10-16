package com.linroid.ketch.core.digest

import com.linroid.ketch.api.ChecksumAlgorithm
import java.security.NoSuchAlgorithmException

/** The JDK's digests; on Android, Conscrypt's, which use the CPU's SHA instructions. */
internal actual fun platformDigest(algorithm: ChecksumAlgorithm): MessageDigest? {
  val name = when (algorithm) {
    ChecksumAlgorithm.MD5 -> "MD5"
    ChecksumAlgorithm.SHA_1 -> "SHA-1"
    ChecksumAlgorithm.SHA_256 -> "SHA-256"
    ChecksumAlgorithm.SHA_512 -> "SHA-512"
  }
  return try {
    JdkDigest(java.security.MessageDigest.getInstance(name))
  } catch (_: NoSuchAlgorithmException) {
    null
  }
}

/** A `java.security.MessageDigest` held to [MessageDigest]'s rules. */
private class JdkDigest(private val delegate: java.security.MessageDigest) : MessageDigest {
  private var finished = false

  override fun update(data: ByteArray, offset: Int, count: Int): MessageDigest {
    check(!finished) { "Digest already finished" }
    checkRange(data, offset, count)
    delegate.update(data, offset, count)
    return this
  }

  override fun digest(): ByteArray {
    check(!finished) { "Digest already finished" }
    finished = true
    return delegate.digest()
  }
}
