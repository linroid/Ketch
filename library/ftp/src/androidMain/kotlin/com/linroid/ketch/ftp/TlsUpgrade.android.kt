package com.linroid.ketch.ftp

import io.ktor.network.sockets.Connection
import io.ktor.network.sockets.Socket
import io.ktor.network.tls.tls
import kotlin.coroutines.CoroutineContext

internal actual suspend fun tlsUpgrade(
  connection: Connection,
  serverName: String,
  context: CoroutineContext,
): Socket? {
  return connection.tls(context) {
    this.serverName = serverName
  }
}
