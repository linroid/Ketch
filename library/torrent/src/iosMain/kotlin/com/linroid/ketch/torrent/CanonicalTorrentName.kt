package com.linroid.ketch.torrent

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping

@OptIn(BetaInteropApi::class)
internal actual fun canonicalTorrentName(value: String): String =
  NSString.create(string = value).precomposedStringWithCanonicalMapping
