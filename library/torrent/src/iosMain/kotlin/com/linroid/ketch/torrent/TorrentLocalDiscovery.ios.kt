package com.linroid.ketch.torrent

/**
 * iOS lets apps send and receive multicast only with Apple's multicast networking entitlement,
 * which Ketch does not have, so local service discovery does not run there.
 */
internal actual suspend fun openLsdMulticast(): LsdTransport? = null
