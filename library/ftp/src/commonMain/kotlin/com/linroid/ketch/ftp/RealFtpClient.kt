package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * FTP client implementation using ktor-network raw sockets.
 *
 * Manages a control connection for sending FTP commands and opens
 * separate passive data connections for file transfer.
 *
 * Not thread-safe: use one instance per coroutine.
 *
 * @param host FTP server hostname
 * @param port FTP server port
 * @param bufferSize read buffer size for data transfers
 */
internal class RealFtpClient(
  private val host: String,
  private val port: Int,
  private val bufferSize: Int = 8192,
) : FtpClient {
  private val log = KetchLogger("FtpClient")

  private var selectorManager: SelectorManager? = null
  private var controlSocket: Socket? = null
  private var reader: ByteReadChannel? = null
  private var writer: ByteWriteChannel? = null
  private var restSupported: Boolean? = null

  override val isConnected: Boolean
    get() = controlSocket != null

  override suspend fun connect() {
    try {
      val sm = SelectorManager(Dispatchers.IO)
      selectorManager = sm
      val socket = aSocket(sm).tcp().connect(host, port)
      controlSocket = socket
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
      log.e(e) { "Failed to connect to $host:$port" }
      throw FtpError.fromException(e)
    }
  }

  override suspend fun upgradeToTls() {
    val socket = controlSocket
      ?: throw KetchError.Network(
        Exception("Not connected")
      )

    log.d { "Upgrading to TLS for $host:$port" }

    // Send AUTH TLS
    val authReply = sendCommand("AUTH TLS")
    if (!authReply.isPositiveCompletion) {
      log.e { "AUTH TLS rejected: $authReply" }
      throw FtpError.fromReply(authReply)
    }

    val upgraded = tlsUpgrade(socket, host, port)
    if (upgraded == null) {
      log.e { "TLS not available on this platform" }
      throw KetchError.Unsupported()
    }

    controlSocket = upgraded
    reader = upgraded.openReadChannel()
    writer = upgraded.openWriteChannel(autoFlush = true)

    // Set protection buffer size and protection level
    val pbszReply = sendCommand("PBSZ 0")
    if (!pbszReply.isPositiveCompletion) {
      log.w { "PBSZ failed: $pbszReply" }
    }

    val protReply = sendCommand("PROT P")
    if (!protReply.isPositiveCompletion) {
      log.w { "PROT P failed: $protReply" }
    }

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

      val dataReader = dataSocket.openReadChannel()
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
      withContext(Dispatchers.IO) {
        try {
          dataSocket.close()
        } catch (_: Exception) {
          // Ignore close errors
        }
      }
    }

    // Read the transfer complete reply (226)
    val completeReply = readReply()
    if (!completeReply.isPositiveCompletion) {
      log.w { "Transfer complete reply: $completeReply" }
    } else {
      log.d { "Transfer complete for $path" }
    }
  }

  override suspend fun disconnect() {
    log.v { "Disconnecting from $host:$port" }
    try {
      if (isConnected) {
        sendCommand("QUIT")
      }
    } catch (_: Exception) {
      // Ignore errors during disconnect
    } finally {
      withContext(Dispatchers.IO) {
        try { controlSocket?.close() } catch (_: Exception) {}
        try { selectorManager?.close() } catch (_: Exception) {}
      }
      controlSocket = null
      reader = null
      writer = null
      selectorManager = null
    }
  }

  /**
   * Opens a passive data connection using EPSV (preferred) or
   * PASV as fallback.
   */
  private suspend fun openPassiveDataConnection(): Socket {
    // Try EPSV first (works with IPv6 and IPv4)
    val epsvReply = sendCommand("EPSV")
    if (epsvReply.isPositiveCompletion) {
      val dataPort = parseEpsvPort(epsvReply.message)
      if (dataPort != null) {
        log.d { "EPSV data connection on port $dataPort" }
        return connectDataSocket(host, dataPort)
      }
      log.d { "EPSV response unparseable, falling back to PASV" }
    } else {
      log.d { "EPSV not supported, falling back to PASV" }
    }

    // Fall back to PASV
    val pasvReply = sendCommand("PASV")
    if (!pasvReply.isPositiveCompletion) {
      log.e { "PASV failed: $pasvReply" }
      throw FtpError.fromReply(pasvReply)
    }

    val (dataHost, dataPort) = parsePasvAddress(pasvReply.message)
      ?: throw KetchError.Network(
        Exception("Failed to parse PASV response: ${pasvReply.message}")
      )

    log.d { "PASV data connection to $dataHost:$dataPort" }
    return connectDataSocket(dataHost, dataPort)
  }

  private suspend fun connectDataSocket(
    dataHost: String,
    dataPort: Int,
  ): Socket {
    val sm = selectorManager ?: throw KetchError.Network(
      Exception("Not connected")
    )
    return try {
      aSocket(sm).tcp().connect(dataHost, dataPort)
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
    val code = firstLine.substring(0, 3).toIntOrNull()
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

  companion object {
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
