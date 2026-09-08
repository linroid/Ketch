package com.linroid.ketch.torrent

import java.text.Normalizer

internal actual fun canonicalTorrentName(value: String): String =
  Normalizer.normalize(value, Normalizer.Form.NFC)
