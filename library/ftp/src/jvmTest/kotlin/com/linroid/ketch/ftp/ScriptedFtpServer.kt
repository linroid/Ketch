package com.linroid.ketch.ftp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Serves one scripted FTP session over real loopback sockets: AUTH TLS, PBSZ, PROT,
 * USER, TYPE, EPSV, PASV, RETR and QUIT.
 *
 * Data connections use TLS once PROT P was accepted, unless
 * [sendsPlainData] makes the server ignore it. On clear-text data
 * connections it records what the client sent in [clientDataBytes].
 *
 * @param sslContext the server's TLS, for sessions that negotiate it
 * @param implicitTls starts the control connection with TLS, before the greeting
 * @param refusedTransferReply closes each data connection without data and ends the transfer
 *   with this reply, as a server refusing the connection after its handshake does
 * @param epsvUnreachable answers EPSV with a port nothing listens on, as a server behind a NAT
 *   or proxy that drops those connections appears
 * @param pasvAddress the address PASV replies name, comma-separated; the data port listens on
 *   loopback whatever it says
 * @param sizeReply the reply to SIZE, else 502 as to any command it does not know
 * @param mdtmReply the reply to MDTM, else 502
 */
internal class ScriptedFtpServer(
  private val content: ByteArray,
  private val sslContext: SSLContext? = null,
  private val protReply: String = "200 Protection level set to P",
  private val sendsPlainData: Boolean = false,
  private val epsvUnreachable: Boolean = false,
  private val pasvAddress: String = "127,0,0,1",
  private val implicitTls: Boolean = false,
  private val refusedTransferReply: String? = null,
  private val sizeReply: String? = null,
  private val mdtmReply: String? = null,
) : AutoCloseable {
  private val loopback = InetAddress.getLoopbackAddress()
  private val control = ServerSocket(0, 1, loopback).apply { soTimeout = TIMEOUT_MS }
  private var passive: ServerSocket? = null

  val port: Int = control.localPort

  /** Commands in the order received. */
  val commands: MutableList<String> = CopyOnWriteArrayList()

  /** Data connections that completed a TLS handshake. */
  @Volatile var dataHandshakes = 0
    private set

  /** Bytes the client sent on the last clear-text data connection. */
  @Volatile var clientDataBytes = ByteArray(0)
    private set

  @Volatile private var failure: Throwable? = null

  private val worker = thread(isDaemon = true, name = "scripted-ftp-server") {
    try {
      serve()
    } catch (e: Throwable) {
      failure = e
    }
  }

  /** Stops the server and rethrows anything that failed on its thread. */
  override fun close() {
    worker.join(5_000)
    control.close()
    passive?.close()
    failure?.let { throw AssertionError("Scripted FTP server failed", it) }
  }

  private fun serve() {
    var socket: Socket = control.accept().apply { soTimeout = TIMEOUT_MS }
    if (implicitTls) socket = acceptTls(socket)
    var input = socket.getInputStream()
    var output = socket.getOutputStream()
    var protectData = false
    try {
      output.reply("220 Fake FTP ready")
      while (true) {
        val line = input.readLine() ?: break
        commands += line
        when (line.substringBefore(' ').uppercase()) {
          "AUTH" -> {
            output.reply("234 Proceed with negotiation")
            socket = acceptTls(socket)
            input = socket.getInputStream()
            output = socket.getOutputStream()
          }
          "PBSZ" -> output.reply("200 PBSZ=0")
          "PROT" -> {
            output.reply(protReply)
            protectData = line == "PROT P" && protReply.startsWith("2")
          }
          "USER" -> output.reply("230 Logged in")
          "TYPE" -> output.reply("200 Type set to I")
          "SIZE" -> output.reply(sizeReply ?: "502 Command not implemented")
          "MDTM" -> output.reply(mdtmReply ?: "502 Command not implemented")
          "EPSV" -> {
            val port = if (epsvUnreachable) {
              ServerSocket(0, 1, loopback).use { it.localPort }
            } else {
              listenPassive()
            }
            output.reply("229 Entering Extended Passive Mode (|||$port|)")
          }
          "PASV" -> {
            val port = listenPassive()
            output.reply("227 Entering Passive Mode ($pasvAddress,${port / 256},${port % 256})")
          }
          "RETR" -> {
            output.reply("150 Opening BINARY mode data connection")
            val data = checkNotNull(passive).accept().apply { soTimeout = TIMEOUT_MS }
            if (refusedTransferReply != null) {
              data.close()
              output.reply(refusedTransferReply)
              continue
            }
            when {
              !protectData -> sendPlain(data)
              sendsPlainData -> sendPlain(data, awaitClient = true)
              else -> sendOverTls(data)
            }
            output.reply("226 Transfer complete")
          }
          "QUIT" -> {
            output.reply("221 Goodbye")
            break
          }
          else -> output.reply("502 Command not implemented")
        }
      }
    } finally {
      socket.close()
    }
  }

  /** Listens for the next data connection, returning its port. */
  private fun listenPassive(): Int {
    passive?.close()
    val listener = ServerSocket(0, 1, loopback).apply { soTimeout = TIMEOUT_MS }
    passive = listener
    return listener.localPort
  }

  private fun acceptTls(socket: Socket): SSLSocket {
    val factory = checkNotNull(sslContext) { "TLS is not configured" }.socketFactory
    val tls = factory.createSocket(socket, socket.inetAddress.hostAddress, socket.port, true)
      as SSLSocket
    tls.useClientMode = false
    tls.enabledProtocols = arrayOf("TLSv1.2")
    tls.startHandshake()
    return tls
  }

  private fun sendOverTls(data: Socket) {
    acceptTls(data).use { tls ->
      dataHandshakes++
      tls.outputStream.write(content)
      tls.outputStream.flush()
    }
  }

  /** @param awaitClient waits for the client's first bytes, as a TLS server would */
  private fun sendPlain(data: Socket, awaitClient: Boolean = false) {
    data.use {
      val received = ByteArrayOutputStream()
      if (awaitClient) {
        val first = ByteArray(5)
        val count = it.getInputStream().read(first)
        if (count > 0) received.write(first, 0, count)
      }
      it.getOutputStream().write(content)
      it.shutdownOutput()
      try {
        it.getInputStream().copyTo(received)
      } catch (_: IOException) {
        // A client that gave up on the connection may reset it.
      }
      clientDataBytes = received.toByteArray()
    }
  }

  private fun OutputStream.reply(line: String) {
    write("$line\r\n".toByteArray(Charsets.US_ASCII))
    flush()
  }

  /** Reads one CRLF-terminated line byte by byte, so nothing past it is buffered. */
  private fun InputStream.readLine(): String? {
    val line = StringBuilder()
    while (true) {
      val byte = read()
      if (byte == -1) return if (line.isEmpty()) null else line.toString()
      if (byte == '\n'.code) return line.toString().removeSuffix("\r")
      line.append(byte.toChar())
    }
  }

  private companion object {
    /** Fails a stuck exchange, such as a handshake the client never starts, instead of hanging. */
    const val TIMEOUT_MS = 5_000
  }
}
