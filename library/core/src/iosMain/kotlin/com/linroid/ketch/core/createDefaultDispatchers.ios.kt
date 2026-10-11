@file:OptIn(DelicateCoroutinesApi::class)

package com.linroid.ketch.core

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext

internal actual fun createDefaultDispatchers(): KetchDispatchers {
  val network = newFixedThreadPoolContext(DEFAULT_NETWORK_THREADS, "ketch-network")
  return KetchDispatchers(
    main = network,
    network = network,
    disk = newFixedThreadPoolContext(DEFAULT_DISK_THREADS, "ketch-disk"),
  )
}
