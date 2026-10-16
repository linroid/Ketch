package com.linroid.ketch.cli

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Ketch desktop app running for this user, reached as its `SingleInstance` (`app/desktop`)
 * lets other processes reach it: through the port and token it keeps in the owner-only
 * [ENDPOINT_FILE] of its config directory, then a request over loopback. Keep the two in step.
 */
internal object DesktopApp {
  /** Request the app answers with the address and token of its loopback API server. */
  const val CONNECT_REQUEST = "cli/connect"

  /** A request the app does not know, which it answers with an empty reply. */
  private const val PING_REQUEST = "cli/ping"

  private const val ENDPOINT_FILE = "app.endpoint"

  /** Sent in place of an argument count to mark a request, which expects a reply. */
  private const val REQUEST = -1

  private const val CONNECT_TIMEOUT_MS = 5_000

  /** What the app answered a [request]. */
  sealed interface Reply {
    /** No app runs: no endpoint file, or nothing answers on its port. */
    data object NotRunning : Reply

    /** The app runs but had nothing to say, as for a request it does not know. */
    data object Empty : Reply

    /** The app's reply. */
    data class Text(val text: String) : Reply
  }

  /**
   * Sends [message] to the app whose config directory is [dir], waiting up to [timeout] for its
   * reply.
   */
  fun request(dir: File, message: String, timeout: Duration = 30.seconds): Reply {
    val endpoint = runCatching { File(dir, ENDPOINT_FILE).readLines() }.getOrNull()
    val port = endpoint?.getOrNull(0)?.trim()?.toIntOrNull() ?: return Reply.NotRunning
    val token = endpoint.getOrNull(1)?.trim() ?: return Reply.NotRunning
    return try {
      Socket().use { socket ->
        val address = InetSocketAddress(InetAddress.getLoopbackAddress(), port)
        socket.connect(address, CONNECT_TIMEOUT_MS)
        socket.soTimeout = timeout.inWholeMilliseconds.toInt()
        val output = DataOutputStream(socket.getOutputStream().buffered())
        output.writeUTF(token)
        output.writeInt(REQUEST)
        output.writeUTF(message)
        output.flush()
        val reply = DataInputStream(socket.getInputStream().buffered()).readUTF()
        if (reply.isEmpty()) Reply.Empty else Reply.Text(reply)
      }
    } catch (_: IOException) {
      // An endpoint left by an app that quit, or one that closed without replying.
      Reply.NotRunning
    }
  }

  /** Whether the app whose config directory is [dir] runs. */
  fun isRunning(dir: File): Boolean = request(dir, PING_REQUEST, 5.seconds) != Reply.NotRunning
}
