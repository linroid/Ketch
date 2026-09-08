package com.linroid.ketch.torrent

import com.linroid.ketch.api.KetchError

internal actual fun createTorrentEngine(
  config: TorrentConfig,
): TorrentEngine {
  throw KetchError.Unsupported(
    cause = UnsupportedOperationException(
      "BitTorrent is not yet supported on iOS"
    ),
  )
}
