package com.linroid.ketch.ftp

import io.ktor.network.sockets.Socket

internal actual suspend fun tlsUpgrade(
  socket: Socket,
  host: String,
  port: Int,
): Socket? = null
