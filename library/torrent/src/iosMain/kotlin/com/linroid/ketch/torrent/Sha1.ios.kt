package com.linroid.ketch.torrent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha1Digest(data: ByteArray): ByteArray {
  val digest = UByteArray(CC_SHA1_DIGEST_LENGTH)
  data.usePinned { pinned ->
    digest.usePinned { digestPinned ->
      CC_SHA1(pinned.addressOf(0), data.size.toUInt(), digestPinned.addressOf(0))
    }
  }
  return digest.toByteArray()
}
