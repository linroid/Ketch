package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Connection
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * FTP client implementation using ktor-network raw sockets.
 *
 * Manages a control connection for sending FTP commands and opens
 * separate passive data connections for file transfer. Once
 * [upgradeToTls] has negotiated `PROT P`, every data connection is
 * wrapped in TLS too, with the control connection's server name.
 *
 * Not thread-safe: use one instance per coroutine.
 *
 * @param host FTP server hostname
 * @param port FTP server port
 * @param bufferSize read buffer size for data transfers
 * @param implicitTls starts the control connection with TLS, before the server's greeting
 *   (implicit FTPS); [upgradeToTls] then only asks for protected data connections
 * @param secureSocket wraps a connection in TLS, or returns null
 *   where TLS is unavailable
 * @param selector selects for this client's sockets; every client shares
 *   [sharedSelector] by default, so connections need no thread of their own
 */
internal class RealFtpClient(
  private val host: String,
  private val port: Int,
  private val bufferSize: Int = 8192,
  private val implicitTls: Boolean = false,
  private val secureSocket: suspend (
    connection: Connection,
    serverName: String,
    context: CoroutineContext,
  ) -> Socket? = ::tlsUpgrade,
  private val selector: SelectorManager = sharedSelector,
) : FtpClient {
  private val log = KetchLogger("FtpClient")

  // TLS record loops run outside the caller's job, as ktor advises: a loop
  // failing after a broken handshake must not cancel the download, and the
  // loops end when their socket closes.
  private val tlsContext = Dispatchers.IO + CoroutineExceptionHandler { _, e ->
    log.d { "TLS session ended: ${e.describeCauses()}" }
  }

  private var controlSocket: Socket? = null
  private var reader: ByteReadChannel? = null
  private var writer: ByteWriteChannel? = null
  private var restSupported: Boolean? = null

  /** False once EPSV failed on this connection: data connections then use PASV. */
  private var epsvUsable = true

  /**
   * The address the control connection reached, where data connections go: [host] may resolve
   * to other servers (round-robin DNS), which would not be listening on the passive port.
   */
  private var serverAddress = host

  /** True once the server accepted `PROT P`: data connections use TLS. */
  private var protectData = false

  override val isConnected: Boolean
    get() = controlSocket != null

  override suspend fun connect() {
    try {
      var socket = connectWithin(host, port)
      controlSocket = socket
      socket.remoteAddress.numericHost()?.let { serverAddress = it }
      if (implicitTls) {
        // Implicit FTPS speaks TLS from the first byte, the greeting included.
        val output = socket.openWriteChannel(autoFlush = true)
        socket = secure(Connection(socket, socket.openReadChannel(), output), "control")
        controlSocket = socket
      }
      reader = socket.openReadChannel()
      writer = socket.openWriteChannel(autoFlush = true)

      val welcome = readReply()
      log.d { "Connected to $host:$port: $welcome" }

      if (welcome.isError) {
        throw FtpError.fromReply(welcome)
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is KetchError) throw e
      // A server that refuses or cannot be reached fails the download as a network error, which
      // is retried: one line says why, without a stack trace.
      log.e { "Failed to connect to $host:$port: ${e.describeCauses()}" }
      throw FtpError.fromException(e)
    }
  }

  override suspend fun upgradeToTls() {
    val socket = controlSocket
    val input = reader
    val output = writer
    if (socket == null || input == null || output == null) {
      throw KetchError.Network(Exception("Not connected"))
    }

    // Implicit FTPS secured the control connection as it opened.
    if (!implicitTls) {
      log.d { "Upgrading to TLS for $host:$port" }

      // Send AUTH TLS
      val authReply = sendCommand("AUTH TLS")
      if (!authReply.isPositiveCompletion) {
        log.e { "AUTH TLS rejected: $authReply" }
        throw FtpError.fromReply(authReply)
      }

      // The handshake continues on the channels connect() opened.
      val upgraded = secure(Connection(socket, input, output), "control")

      controlSocket = upgraded
      reader = upgraded.openReadChannel()
      writer = upgraded.openWriteChannel(autoFlush = true)
    }

    // Set protection buffer size and protection level
    val pbszReply = sendCommand("PBSZ 0")
    if (!pbszReply.isPositiveCompletion) {
      log.w { "PBSZ failed: $pbszReply" }
    }

    // Without PROT P the file would travel in clear text under an ftps:// URL.
    val protReply = sendCommand("PROT P")
    if (!protReply.isPositiveCompletion) {
      log.e { "PROT P rejected, refusing clear-text data: $protReply" }
      throw FtpError.fromReply(protReply)
    }
    protectData = true

    log.d { "TLS upgrade complete for $host:$port" }
  }

  override suspend fun login(username: String, password: String) {
    log.d { "Authenticating as '$username'" }
    val userReply = sendCommand("USER $username")
    when (userReply.code) {
      230 -> {
        log.d { "Logged in as $username (no password needed)" }
        return
      }
      331 -> {
        log.v { "Password required for $username" }
      }
      else -> {
        log.e { "USER rejected: $userReply" }
        throw FtpError.fromReply(userReply)
      }
    }

    val passReply = sendCommand("PASS $password")
    if (!passReply.isPositiveCompletion) {
      log.e { "Login failed for '$username': $passReply" }
      throw FtpError.fromReply(passReply)
    }
    log.d { "Logged in as $username" }
  }

  override suspend fun setBinaryMode() {
    val reply = sendCommand("TYPE I")
    if (!reply.isPositiveCompletion) {
      log.e { "TYPE I failed: $reply" }
      throw FtpError.fromReply(reply)
    }
    log.v { "Binary mode set" }
  }

  override suspend fun size(path: String): Long? {
    val reply = sendCommand("SIZE $path")
    if (!reply.isPositiveCompletion) {
      if (reply.code == 550) {
        log.d { "SIZE: file not found — $path" }
        return null
      }
      if (reply.code == 502) {
        log.d { "SIZE: command not supported by server" }
        return null
      }
      log.w { "SIZE failed: $reply" }
      return null
    }
    val size = reply.message.trim().toLongOrNull()
    log.d { "SIZE $path = $size bytes" }
    return size
  }

  override suspend fun mdtm(path: String): String? {
    val reply = sendCommand("MDTM $path")
    if (!reply.isPositiveCompletion) {
      if (reply.code == 550) {
        log.d { "MDTM: file not found — $path" }
        return null
      }
      if (reply.code == 502) {
        log.d { "MDTM: command not supported by server" }
        return null
      }
      log.w { "MDTM failed: $reply" }
      return null
    }
    val timestamp = reply.message.trim()
    log.d { "MDTM $path = $timestamp" }
    return timestamp
  }

  override suspend fun supportsRest(): Boolean {
    restSupported?.let { return it }
    val reply = sendCommand("REST 0")
    val supported = reply.code == 350
    restSupported = supported
    log.d { "REST support: $supported" }
    return supported
  }

  override suspend fun retrieve(
    path: String,
    offset: Long,
    onData: suspend (ByteArray) -> Unit,
  ) {
    log.d { "RETR $path (offset=$offset)" }
    val dataSocket = openPassiveDataConnection()
    var transferSocket = dataSocket
    try {
      if (offset > 0) {
        val restReply = sendCommand("REST $offset")
        if (restReply.code != 350) {
          log.e { "REST $offset rejected: $restReply" }
          throw FtpError.fromReply(restReply)
        }
      }

      val retrReply = sendCommand("RETR $path")
      if (!retrReply.isPositivePreliminary &&
        !retrReply.isPositiveCompletion
      ) {
        log.e { "RETR rejected: $retrReply" }
        throw FtpError.fromReply(retrReply)
      }

      // Servers start the data connection's TLS handshake after the
      // preliminary reply, so it must come after RETR, as in RFC 4217.
      if (protectData) transferSocket = secure(dataSocket.connection(), "data")

      val dataReader = transferSocket.openReadChannel()
      val buffer = ByteArray(bufferSize)
      while (true) {
        currentCoroutineContext().ensureActive()
        val bytesRead = dataReader.readAvailable(buffer)
        if (bytesRead <= 0) break
        val chunk = if (bytesRead == buffer.size) {
          buffer
        } else {
          buffer.copyOf(bytesRead)
        }
        onData(chunk)
      }
    } finally {
      // Also when the transfer was cancelled: a pause, or another connection taking its range.
      withContext(Dispatchers.IO + NonCancellable) {
        try {
          transferSocket.close()
        } catch (_: Exception) {
          // Ignore close errors
        }
      }
    }

    // Read the transfer complete reply (226). An error says why the data ended, as a data
    // connection the server refused after its handshake: report that, not a short transfer.
    val completeReply = readReply()
    if (completeReply.isError) {
      log.w { "Transfer failed: $completeReply" }
      throw FtpError.fromReply(completeReply)
    } else if (!completeReply.isPositiveCompletion) {
      log.w { "Transfer complete reply: $completeReply" }
    } else {
      log.d { "Transfer complete for $path" }
    }
  }

  override suspend fun disconnect() {
    log.v { "Disconnecting from $host:$port" }
    try {
      // A server that stopped answering must not hold the segment that gave up on it.
      if (isConnected) withWallClockTimeout(QUIT_TIMEOUT) { sendCommand("QUIT") }
    } catch (_: Exception) {
      // Ignore errors during disconnect
    } finally {
      withContext(Dispatchers.IO + NonCancellable) {
        try { controlSocket?.close() } catch (_: Exception) {}
      }
      controlSocket = null
      reader = null
      writer = null
      protectData = false
    }
  }

  /**
   * Wraps [connection] in TLS for the control host.
   *
   * ktor-network-tls cannot resume TLS sessions, so servers that
   * require the data connection to reuse the control connection's
   * session refuse it, and this fails.
   *
   * @param channel "control" or "data", for logs and errors
   * @throws KetchError.Network when the handshake fails
   * @throws KetchError.Unsupported where TLS is unavailable
   */
  private suspend fun secure(connection: Connection, channel: String): Socket {
    val upgraded = try {
      secureSocket(connection, host, tlsContext)
    } catch (e: Exception) {
      // A TLS channel closed under the handshake also throws
      // CancellationException; only a cancelled caller rethrows it.
      if (e is CancellationException) currentCoroutineContext().ensureActive()
      log.w { "TLS handshake failed on the $channel connection: ${e.describeCauses()}" }
      throw KetchError.Network(
        Exception("TLS handshake failed on the FTPS $channel connection", e)
      )
    }
    if (upgraded == null) {
      log.e { "TLS not available on this platform" }
      throw KetchError.Unsupported(
        UnsupportedOperationException("FTPS is not available on this platform"),
      )
    }
    return upgraded
  }

  /**
   * Opens a passive data connection using EPSV (preferred) or PASV as fallback: when the server
   * refuses EPSV, or the port it names cannot be reached, as behind some NATs and proxies. The
   * connection then skips EPSV.
   *
   * Both connect to the address the control connection reached ([serverAddress]). The address a
   * PASV reply names is ignored, as curl does: servers behind NAT name a private address or
   * `0.0.0.0`, and a server must not send the client to another host.
   */
  private suspend fun openPassiveDataConnection(): Socket {
    if (epsvUsable) {
      // Try EPSV first (works with IPv6 and IPv4)
      val epsvReply = sendCommand("EPSV")
      val dataPort = if (epsvReply.isPositiveCompletion) {
        parseEpsvPort(epsvReply.message)
          .also { if (it == null) log.d { "EPSV response unparseable, falling back to PASV" } }
      } else {
        log.d { "EPSV not supported, falling back to PASV" }
        null
      }
      if (dataPort != null) {
        log.d { "EPSV data connection on port $dataPort" }
        try {
          return connectDataSocket(serverAddress, dataPort)
        } catch (e: KetchError.Network) {
          log.d { "EPSV data connection failed, falling back to PASV: ${e.describeCauses()}" }
        }
      }
      epsvUsable = false
    }

    val pasvReply = sendCommand("PASV")
    if (!pasvReply.isPositiveCompletion) {
      log.e { "PASV failed: $pasvReply" }
      throw FtpError.fromReply(pasvReply)
    }

    val (dataHost, dataPort) = parsePasvAddress(pasvReply.message)
      ?: throw KetchError.Network(
        Exception("Failed to parse PASV response: ${pasvReply.message}")
      )
    log.d {
      "PASV data connection on port $dataPort" +
        if (dataHost != serverAddress) ", ignoring the address the reply named" else ""
    }
    return connectDataSocket(serverAddress, dataPort)
  }

  private suspend fun connectDataSocket(
    dataHost: String,
    dataPort: Int,
  ): Socket {
    if (!isConnected) throw KetchError.Network(Exception("Not connected"))
    return try {
      connectWithin(dataHost, dataPort)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw KetchError.Network(e)
    }
  }

  /**
   * Sends a command and reads the reply.
   */
  private suspend fun sendCommand(command: String): FtpReply {
    val w = writer ?: throw KetchError.Network(
      Exception("Not connected")
    )
    val logCommand = if (command.startsWith("PASS ")) {
      "PASS ***"
    } else {
      command
    }
    log.v { "> $logCommand" }
    try {
      w.writeStringUtf8("$command\r\n")
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw KetchError.Network(e)
    }
    return readReply()
  }

  /**
   * Reads an FTP reply from the control connection.
   *
   * Handles multi-line replies (RFC 959: first line has "NNN-",
   * last line has "NNN ").
   */
  private suspend fun readReply(): FtpReply {
    val r = reader ?: throw KetchError.Network(
      Exception("Not connected")
    )
    val lines = mutableListOf<String>()

    try {
      val firstLine = readLine(r)
      lines.add(firstLine)

      if (firstLine.length >= 4 && firstLine[3] == '-') {
        // Multi-line reply: read until "NNN " line
        val codePrefix = firstLine.substring(0, 3)
        while (true) {
          val line = readLine(r)
          lines.add(line)
          if (line.length >= 4 &&
            line.substring(0, 3) == codePrefix &&
            line[3] == ' '
          ) {
            break
          }
        }
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      if (e is KetchError) throw e
      throw KetchError.Network(e)
    }

    val firstLine = lines.first()
    val code = firstLine.take(3).toIntOrNull()
      ?: throw KetchError.Network(
        Exception("Invalid FTP reply: $firstLine")
      )
    val message = if (firstLine.length > 4) {
      firstLine.substring(4)
    } else {
      ""
    }

    val reply = FtpReply(code, message)
    log.v { "< $reply" }
    return reply
  }

  /**
   * Reads a single line (terminated by CRLF) from the channel.
   */
  private suspend fun readLine(channel: ByteReadChannel): String {
    val sb = StringBuilder()
    val buf = ByteArray(1)
    var prevCr = false
    while (true) {
      val read = channel.readAvailable(buf)
      if (read <= 0) break
      val ch = buf[0].toInt().toChar()
      if (ch == '\n' && prevCr) {
        // Remove trailing CR
        if (sb.isNotEmpty() && sb.last() == '\r') {
          sb.deleteAt(sb.lastIndex)
        }
        break
      }
      prevCr = (ch == '\r')
      sb.append(ch)
    }
    return sb.toString()
  }

  /**
   * Connects to [host] and [port], failing with [KetchError.Network] after [CONNECT_TIMEOUT].
   * Transfers have no read timeout: the download's transfer watchdog drops stalled ones.
   */
  private suspend fun connectWithin(host: String, port: Int): Socket =
    withWallClockTimeout(CONNECT_TIMEOUT) { aSocket(selector).tcp().connect(host, port) }
      ?: throw KetchError.Network(Exception("Connection timed out after $CONNECT_TIMEOUT"))

  /**
   * Runs [block] for at most [timeout] of real time, `null` once it runs out: on
   * [Dispatchers.IO], whose clock a test's virtual time does not advance while a socket waits.
   */
  private suspend fun <T> withWallClockTimeout(timeout: Duration, block: suspend () -> T): T? =
    withContext(Dispatchers.IO) { withTimeoutOrNull(timeout) { block() } }

  companion object {
    /** This address as a numeric IPv4 or IPv6 host, `null` when it was not resolved. */
    internal fun SocketAddress.numericHost(): String? {
      val bytes = (this as? InetSocketAddress)?.resolveAddress() ?: return null
      return when (bytes.size) {
        4 -> bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
        16 -> (0 until 8).joinToString(":") { group ->
          val high = bytes[2 * group].toInt() and 0xff
          (high shl 8 or (bytes[2 * group + 1].toInt() and 0xff)).toString(16)
        }
        else -> null
      }
    }

    /**
     * The selector every client shares by default, created on first use and never closed: it
     * waits for every client's sockets on one thread, where a selector per client took one each.
     * Idle, the JVM's suspends; the native (iOS) one keeps an IO thread waiting in `pselect`.
     */
    internal val sharedSelector: SelectorManager by lazy { SelectorManager(Dispatchers.IO) }

    /** How long a control or data connection may take to open. */
    internal val CONNECT_TIMEOUT: Duration = 30.seconds

    /** How long [disconnect] waits for the reply to `QUIT`. */
    internal val QUIT_TIMEOUT: Duration = 5.seconds

    /**
     * Parses EPSV response to extract port number.
     * Format: "Entering Extended Passive Mode (|||port|)"
     */
    internal fun parseEpsvPort(message: String): Int? {
      val regex = Regex("""\(\|\|\|(\d+)\|\)""")
      return regex.find(message)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Parses PASV response to extract host and port.
     * Format: "Entering Passive Mode (h1,h2,h3,h4,p1,p2)"
     */
    internal fun parsePasvAddress(
      message: String,
    ): Pair<String, Int>? {
      val regex = Regex("""\((\d+),(\d+),(\d+),(\d+),(\d+),(\d+)\)""")
      val match = regex.find(message) ?: return null
      val (h1, h2, h3, h4, p1, p2) = match.destructured
      val host = "$h1.$h2.$h3.$h4"
      val port = p1.toInt() * 256 + p2.toInt()
      return host to port
    }
  }
}
