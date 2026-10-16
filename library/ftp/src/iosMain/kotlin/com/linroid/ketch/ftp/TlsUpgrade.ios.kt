package com.linroid.ketch.ftp

import io.ktor.network.sockets.Connection
import io.ktor.network.sockets.Socket
import kotlin.coroutines.CoroutineContext

internal actual suspend fun tlsUpgrade(
  connection: Connection,
  serverName: String,
  context: CoroutineContext,
): Socket? = null
