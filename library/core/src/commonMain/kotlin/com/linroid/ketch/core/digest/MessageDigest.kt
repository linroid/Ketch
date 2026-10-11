package com.linroid.ketch.core.digest

import com.linroid.ketch.api.ChecksumAlgorithm

/**
 * An incremental hash function, written in pure Kotlin so it runs on every target the engine
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

/** A new, empty [MessageDigest] of this algorithm. */
fun ChecksumAlgorithm.newDigest(): MessageDigest = when (this) {
  ChecksumAlgorithm.MD5 -> Md5()
  ChecksumAlgorithm.SHA_1 -> Sha1()
  ChecksumAlgorithm.SHA_256 -> Sha256()
  ChecksumAlgorithm.SHA_512 -> Sha512()
}

/** Checks [offset] and [count] against [data] as every [MessageDigest.update] does. */
internal fun checkRange(data: ByteArray, offset: Int, count: Int) {
  require(offset >= 0 && count >= 0 && offset <= data.size - count) { "Invalid range" }
}
