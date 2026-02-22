@file:OptIn(DelicateCoroutinesApi::class)

package com.linroid.ketch.core

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext

internal actual fun createDefaultDispatchers(
  networkPoolSize: Int,
  ioPoolSize: Int,
): KetchDispatchers = KetchDispatchers(
  main = newSingleThreadContext("ketch-main"),
  network = newFixedThreadPoolContext(networkPoolSize, "ketch-network"),
  io = newFixedThreadPoolContext(ioPoolSize, "ketch-io"),
)
