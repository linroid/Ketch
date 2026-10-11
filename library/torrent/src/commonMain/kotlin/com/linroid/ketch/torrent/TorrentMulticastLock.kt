package com.linroid.ketch.torrent

/**
 * Keeps the network interface receiving multicast while local service discovery runs, as
 * Android's Wi-Fi `MulticastLock` does; hosts that filter multicast without one pass it to
 * [TorrentDownloadSource].
 */
fun interface TorrentMulticastLock {
  /** Takes the lock; closing the result releases it. */
  fun acquire(): AutoCloseable
}
