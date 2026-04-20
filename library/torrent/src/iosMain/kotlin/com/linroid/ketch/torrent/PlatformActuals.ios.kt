package com.linroid.ketch.torrent

import com.linroid.ketch.api.KetchError
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

internal actual fun createTorrentEngine(
  config: TorrentConfig,
): TorrentEngine {
  throw KetchError.Unsupported(
    cause = UnsupportedOperationException(
      "BitTorrent is not yet supported on iOS"
    ),
  )
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun encodeBase64(data: ByteArray): String {
  if (data.isEmpty()) return ""
  memScoped {
    val nsData = NSData.dataWithBytes(
      allocArrayOf(data),
      data.size.toULong(),
    )
    return nsData.base64EncodedStringWithOptions(0u)
  }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun decodeBase64(data: String): ByteArray {
  if (data.isEmpty()) return ByteArray(0)
  val nsData: NSData = NSData.create(
    base64EncodedString = data,
    options = 0u,
  ) ?: throw IllegalArgumentException("Invalid base64 string")
  val size = nsData.length.toInt()
  if (size == 0) return ByteArray(0)
  val result = ByteArray(size)
  result.usePinned { pinned ->
    memcpy(pinned.addressOf(0), nsData.bytes, nsData.length)
  }
  return result
}
