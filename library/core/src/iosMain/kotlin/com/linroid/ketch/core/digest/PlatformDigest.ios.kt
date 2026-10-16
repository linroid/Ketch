@file:OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)

package com.linroid.ketch.core.digest

import com.linroid.ketch.api.ChecksumAlgorithm
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1_CTX
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA1_Final
import platform.CoreCrypto.CC_SHA1_Init
import platform.CoreCrypto.CC_SHA1_Update
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.CoreCrypto.CC_SHA512_CTX
import platform.CoreCrypto.CC_SHA512_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA512_Final
import platform.CoreCrypto.CC_SHA512_Init
import platform.CoreCrypto.CC_SHA512_Update

/** CommonCrypto's digests; MD5 stays pure Kotlin, as CommonCrypto deprecates it. */
internal actual fun platformDigest(algorithm: ChecksumAlgorithm): MessageDigest? =
  when (algorithm) {
    ChecksumAlgorithm.MD5 -> null
    ChecksumAlgorithm.SHA_1 -> CommonCryptoDigest(sizeOf<CC_SHA1_CTX>(), CC_SHA1_DIGEST_LENGTH,
      init = { CC_SHA1_Init(it.reinterpret()) },
      update = { context, data, count -> CC_SHA1_Update(context.reinterpret(), data, count) },
      final = { digest, context -> CC_SHA1_Final(digest, context.reinterpret()) })
    ChecksumAlgorithm.SHA_256 -> CommonCryptoDigest(sizeOf<CC_SHA256_CTX>(),
      CC_SHA256_DIGEST_LENGTH,
      init = { CC_SHA256_Init(it.reinterpret()) },
      update = { context, data, count -> CC_SHA256_Update(context.reinterpret(), data, count) },
      final = { digest, context -> CC_SHA256_Final(digest, context.reinterpret()) })
    ChecksumAlgorithm.SHA_512 -> CommonCryptoDigest(sizeOf<CC_SHA512_CTX>(),
      CC_SHA512_DIGEST_LENGTH,
      init = { CC_SHA512_Init(it.reinterpret()) },
      update = { context, data, count -> CC_SHA512_Update(context.reinterpret(), data, count) },
      final = { digest, context -> CC_SHA512_Final(digest, context.reinterpret()) })
  }

/**
 * One CommonCrypto digest. Its context lives in a [LongArray], aligned for the 64-bit words the
 * contexts hold and pinned only for each call, so an abandoned digest leaves nothing to free.
 */
private class CommonCryptoDigest(
  contextBytes: Long,
  private val length: Int,
  init: (CPointer<LongVar>) -> Int,
  private val update: (CPointer<LongVar>, CPointer<ByteVar>, UInt) -> Int,
  private val final: (CPointer<UByteVar>, CPointer<LongVar>) -> Int,
) : MessageDigest {
  private val context = LongArray(((contextBytes + 7) / 8).toInt())
  private var finished = false

  init {
    context.usePinned { init(it.addressOf(0)) }
  }

  override fun update(data: ByteArray, offset: Int, count: Int): MessageDigest {
    check(!finished) { "Digest already finished" }
    checkRange(data, offset, count)
    if (count == 0) return this
    context.usePinned { pinnedContext ->
      data.usePinned { pinnedData ->
        update(pinnedContext.addressOf(0), pinnedData.addressOf(offset), count.toUInt())
      }
    }
    return this
  }

  override fun digest(): ByteArray {
    check(!finished) { "Digest already finished" }
    finished = true
    val digest = UByteArray(length)
    context.usePinned { pinnedContext ->
      digest.usePinned { final(it.addressOf(0), pinnedContext.addressOf(0)) }
    }
    return digest.asByteArray()
  }
}
