package com.linroid.ketch.ftp

import io.ktor.network.sockets.Socket

/**
 * Upgrades a plain TCP [Socket] to a TLS-secured socket.
 *
 * Returns null if TLS is not supported on the current platform
 * (e.g., iOS, where ktor-network-tls is not available).
 *
 * @param socket the plain TCP socket to upgrade
 * @param host the server hostname for SNI
 * @param port the server port
 * @return a TLS-wrapped socket, or null if not supported
 */
internal expect suspend fun tlsUpgrade(
  socket: Socket,
  host: String,
  port: Int,
): Socket?
