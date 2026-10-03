package com.linroid.ketch.app.desktop

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Keeps one desktop app per user. Windows and Linux start a new process for every file opened
 * with Ketch, so a later launch hands its arguments to the running app over loopback and exits.
 * macOS delivers opened files to the running app itself. Other processes of this user, such as
 * the browser extension's native messaging host, can also [request] a reply from the running app.
 *
 * A token in the owner-only endpoint file keeps other users on the machine from reaching it.
 */
internal class SingleInstance private constructor(
  private val lock: FileLock?,
  private val server: ServerSocket?,
) : AutoCloseable {

  override fun close() {
    server?.close()
    lock?.channel()?.close()
  }

  companion object {
    private const val LOCK_FILE = "app.lock"
    private const val ENDPOINT_FILE = "app.endpoint"
    private const val MAX_ARGUMENTS = 256
    private const val CONNECT_ATTEMPTS = 20
    private const val RETRY_DELAY_MS = 250L
    private const val TIMEOUT_MS = 5_000

    /** Sent in place of an argument count to mark a [request], which expects a reply. */
    private const val REQUEST = -1

    /** How long a [request] waits for its reply; the app may still be starting up. */
    private const val REPLY_TIMEOUT_MS = 30_000

    /**
     * Becomes the running instance and passes arguments forwarded by later launches to
     * [onArguments] on a background thread. Returns null once [args] are forwarded to the
     * instance already running, meaning this process should exit.
     *
     * [onRequest] answers each [request] on a thread of its own, so it may block; null sends
     * no reply.
     *
     * If the running instance cannot be reached, this launch proceeds on its own.
     */
    fun acquire(
      dir: File,
      args: List<String>,
      onRequest: (String) -> String? = { null },
      onArguments: (List<String>) -> Unit,
    ): SingleInstance? {
      dir.mkdirs()
      val channel = FileChannel.open(
        File(dir, LOCK_FILE).toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
      )
      val lock = try {
        channel.tryLock()
      } catch (_: OverlappingFileLockException) {
        // Held by this JVM already, which only happens when tests start two instances.
        null
      } catch (e: IOException) {
        System.err.println("Ketch: single-instance lock unavailable: ${e.message}")
        null
      }
      if (lock == null) {
        channel.close()
        if (forward(dir, args)) return null
        System.err.println("Ketch: the running instance did not respond; starting anyway")
        return SingleInstance(null, null)
      }
      val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
      val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
      writeEndpoint(File(dir, ENDPOINT_FILE), "${server.localPort}\n$token")
      thread(isDaemon = true, name = "ketch-single-instance") {
        while (!server.isClosed) {
          val socket = try {
            server.accept()
          } catch (_: IOException) {
            continue // Closed on exit.
          }
          if (server.isClosed) {
            // close() returns before this thread lets go of the socket, so a connection made
            // after it can still be accepted; an instance that closed answers none.
            socket.close()
            break
          }
          try {
            socket.soTimeout = TIMEOUT_MS
            when (val message = receive(socket, token)) {
              is Message.Arguments -> {
                socket.close()
                onArguments(message.args)
              }
              is Message.Request -> thread(isDaemon = true, name = "ketch-single-instance-reply") {
                reply(socket, onRequest, message.text)
              }
              null -> socket.close()
            }
          } catch (_: IOException) {
            // A client that disconnected early.
            socket.close()
          }
        }
      }
      return SingleInstance(lock, server)
    }

    /**
     * Sends [message] to the running instance and returns its reply, or null when no instance
     * is running, it did not answer in time, or it had no reply.
     */
    fun request(dir: File, message: String): String? {
      val (port, token) = readEndpoint(dir) ?: return null
      return try {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MS)
          socket.soTimeout = REPLY_TIMEOUT_MS
          val output = DataOutputStream(socket.getOutputStream().buffered())
          output.writeUTF(token)
          output.writeInt(REQUEST)
          output.writeUTF(message)
          output.flush()
          DataInputStream(socket.getInputStream().buffered()).readUTF().ifEmpty { null }
        }
      } catch (_: IOException) {
        null
      }
    }

    private sealed interface Message {
      class Arguments(val args: List<String>) : Message
      class Request(val text: String) : Message
    }

    private fun receive(socket: Socket, token: String): Message? {
      val input = DataInputStream(socket.getInputStream().buffered())
      val received = input.readUTF()
      if (!MessageDigest.isEqual(received.toByteArray(), token.toByteArray())) return null
      val count = input.readInt()
      if (count == REQUEST) return Message.Request(input.readUTF())
      if (count !in 0..MAX_ARGUMENTS) return null
      return Message.Arguments(List(count) { input.readUTF() })
    }

    private fun reply(socket: Socket, onRequest: (String) -> String?, request: String) {
      socket.use {
        val reply = try {
          onRequest(request)
        } catch (e: Exception) {
          System.err.println("Ketch: could not answer a request: ${e.message}")
          null
        }
        try {
          val output = DataOutputStream(it.getOutputStream().buffered())
          output.writeUTF(reply.orEmpty())
          output.flush()
        } catch (_: IOException) {
          // The requester gave up waiting.
        }
      }
    }

    private fun readEndpoint(dir: File): Pair<Int, String>? {
      val endpoint = runCatching { File(dir, ENDPOINT_FILE).readLines() }.getOrNull()
      val port = endpoint?.getOrNull(0)?.toIntOrNull() ?: return null
      val token = endpoint.getOrNull(1) ?: return null
      return port to token
    }

    private fun forward(dir: File, args: List<String>): Boolean {
      // The running instance may still be starting and not have published its endpoint yet.
      repeat(CONNECT_ATTEMPTS) {
        val endpoint = readEndpoint(dir)
        if (endpoint != null) {
          val (port, token) = endpoint
          try {
            Socket().use { socket ->
              socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MS)
              val output = DataOutputStream(socket.getOutputStream().buffered())
              output.writeUTF(token)
              output.writeInt(args.size)
              args.forEach(output::writeUTF)
              output.flush()
            }
            return true
          } catch (_: IOException) {
            // Stale endpoint from an instance that is exiting; retry below.
          }
        }
        Thread.sleep(RETRY_DELAY_MS)
      }
      return false
    }

    private fun writeEndpoint(file: File, content: String) {
      val temp = File(file.parentFile, "${file.name}.tmp").toPath()
      Files.deleteIfExists(temp)
      try {
        Files.createFile(temp, PosixFilePermissions.asFileAttribute(
          PosixFilePermissions.fromString("rw-------"),
        ))
      } catch (_: UnsupportedOperationException) {
        // Windows: the per-user profile directory already restricts access.
        Files.createFile(temp)
      }
      Files.write(temp, content.toByteArray())
      Files.move(
        temp, file.toPath(),
        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
      )
    }
  }
}
