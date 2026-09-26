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
 * macOS delivers opened files to the running app itself.
 *
 * A token in the owner-only endpoint file keeps other users on the machine from sending files.
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

    /**
     * Becomes the running instance and passes arguments forwarded by later launches to
     * [onArguments] on a background thread. Returns null once [args] are forwarded to the
     * instance already running, meaning this process should exit.
     *
     * If the running instance cannot be reached, this launch proceeds on its own.
     */
    fun acquire(
      dir: File,
      args: List<String>,
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
          try {
            server.accept().use { socket ->
              socket.soTimeout = TIMEOUT_MS
              receive(socket, token)?.let(onArguments)
            }
          } catch (_: IOException) {
            // Closed on exit, or a client that disconnected early.
          }
        }
      }
      return SingleInstance(lock, server)
    }

    private fun receive(socket: Socket, token: String): List<String>? {
      val input = DataInputStream(socket.getInputStream().buffered())
      val received = input.readUTF()
      if (!MessageDigest.isEqual(received.toByteArray(), token.toByteArray())) return null
      val count = input.readInt()
      if (count !in 0..MAX_ARGUMENTS) return null
      return List(count) { input.readUTF() }
    }

    private fun forward(dir: File, args: List<String>): Boolean {
      // The running instance may still be starting and not have published its endpoint yet.
      repeat(CONNECT_ATTEMPTS) {
        val endpoint = runCatching { File(dir, ENDPOINT_FILE).readLines() }.getOrNull()
        val port = endpoint?.getOrNull(0)?.toIntOrNull()
        val token = endpoint?.getOrNull(1)
        if (port != null && token != null) {
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
