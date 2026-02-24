package com.linroid.ketch.ftp

import io.ktor.network.sockets.Socket
import io.ktor.network.tls.tls
import kotlin.coroutines.coroutineContext

internal actual suspend fun tlsUpgrade(
  socket: Socket,
  host: String,
  port: Int,
): Socket? {
  return socket.tls(coroutineContext) {
    serverName = host
  }
}
