package com.linroid.ketch.torrent.tracker

import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.V2InfoHash

/** Full content topics remain distinct even when their 20-byte tracker wire forms collide. */
internal sealed interface TrackerTopic {
  data class V1(val hash: InfoHash) : TrackerTopic
  data class V2(val hash: V2InfoHash) : TrackerTopic

  /** BEP 3 uses SHA-1; BEP 52 uses the first 20 bytes of the full SHA-256 info hash. */
  fun wireBytes(): ByteArray = when (this) {
    is V1 -> hash.toBytes()
    is V2 -> hash.wireBytes()
  }
}
