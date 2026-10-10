package com.linroid.ketch.ftp

import io.ktor.network.sockets.Connection
import io.ktor.network.sockets.Socket
import kotlin.coroutines.CoroutineContext

/**
 * Upgrades a plain TCP connection to a TLS-secured socket.
 *
 * Returns null if TLS is not supported on the current platform
 * (e.g., iOS, where ktor-network-tls is not available).
 *
 * @param connection the plain TCP socket with its read and write
 *   channels, which the handshake takes over: a socket's channels can
 *   only be opened once
 * @param serverName the control connection's host, sent for SNI and
 *   checked against the server certificate
 * @param context where the TLS session's record loops run
 * @return a TLS-wrapped socket, or null if not supported
 */
internal expect suspend fun tlsUpgrade(
  connection: Connection,
  serverName: String,
  context: CoroutineContext,
): Socket?
