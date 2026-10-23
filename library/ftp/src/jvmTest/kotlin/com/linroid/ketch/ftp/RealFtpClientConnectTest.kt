package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RealFtpClientConnectTest {

  private class Record(val level: LogLevel, val message: String, val throwable: Throwable?)

  /** Keeps the debug records, warnings and errors it is given. */
  private class Capture : Logger {
    val records = mutableListOf<Record>()
    override fun v(message: String) {}
    override fun d(message: String) {
      records += Record(LogLevel.DEBUG, message, null)
    }
    override fun i(message: String) {}
    override fun w(message: String, throwable: Throwable?) {
      records += Record(LogLevel.WARN, message, throwable)
    }
    override fun e(message: String, throwable: Throwable?) {
      records += Record(LogLevel.ERROR, message, throwable)
    }
  }

  @Test
  fun connect_refused_logsWhyAtDebugAndNamesTheServer() {
    // A port nothing listens on any more refuses the connection.
    val loopback = InetAddress.getLoopbackAddress()
    val port = ServerSocket(0, 1, loopback).use { it.localPort }
    val capture = Capture()
    KetchLogger.setLogger(capture)
    val error = try {
      assertFailsWith<KetchError.Network> {
        runBlocking { RealFtpClient(loopback.hostAddress, port).connect() }
      }
    } finally {
      KetchLogger.setLogger(Logger.None)
    }

    // Callers retry it, warning as they do, and report where it failed.
    val record = capture.records.single { "Failed to connect" in it.message }
    assertEquals(LogLevel.DEBUG, record.level)
    assertTrue("ConnectException" in record.message, record.message)
    assertTrue(capture.records.none { it.level >= LogLevel.WARN })
    val causes = error.describeCauses()
    assertTrue("Cannot connect to ${loopback.hostAddress}:$port" in causes, causes)
  }

  @Test
  fun connect_unresolvedHost_namesTheHost() {
    val error = assertFailsWith<KetchError.Network> {
      runBlocking { RealFtpClient("ftp.invalid", 21).connect() }
    }

    val causes = error.describeCauses()
    assertTrue("Cannot connect to ftp.invalid:21" in causes, causes)
  }
}
