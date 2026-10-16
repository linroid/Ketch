package com.linroid.ketch.core.digest

import com.linroid.ketch.api.ChecksumAlgorithm

/**
 * An incremental hash function. [newDigest] gives the platform's implementation where it has one;
 * [Md5], [Sha1], [Sha256] and [Sha512] are pure Kotlin and run on every target the engine
 * supports. Instances are confined to one owner and cannot be updated after [digest].
 */
interface MessageDigest {
  /**
   * Adds [count] bytes of [data] from [offset] to the message.
   *
   * @throws IllegalArgumentException when the range lies outside [data]
   * @throws IllegalStateException after [digest]
   */
  fun update(data: ByteArray, offset: Int = 0, count: Int = data.size - offset): MessageDigest

  /**
   * Finishes the message and returns its digest.
   *
   * @throws IllegalStateException when called twice
   */
  fun digest(): ByteArray
}

/**
 * A new, empty [MessageDigest] of this algorithm: the platform's (`java.security.MessageDigest` on
 * the JVM and Android, CommonCrypto on iOS), which is several times faster, else the pure Kotlin
 * one.
 */
fun ChecksumAlgorithm.newDigest(): MessageDigest = platformDigest(this) ?: pureDigest()

/** The pure Kotlin [MessageDigest] of this algorithm, which every target has. */
internal fun ChecksumAlgorithm.pureDigest(): MessageDigest = when (this) {
  ChecksumAlgorithm.MD5 -> Md5()
  ChecksumAlgorithm.SHA_1 -> Sha1()
  ChecksumAlgorithm.SHA_256 -> Sha256()
  ChecksumAlgorithm.SHA_512 -> Sha512()
}

/** The platform's implementation of [algorithm], or null where it has none. */
internal expect fun platformDigest(algorithm: ChecksumAlgorithm): MessageDigest?

/** Checks [offset] and [count] against [data] as every [MessageDigest.update] does. */
internal fun checkRange(data: ByteArray, offset: Int, count: Int) {
  require(offset >= 0 && count >= 0 && offset <= data.size - count) { "Invalid range" }
}
