package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.Logger
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealFtpClientConnectTest {

  private class Record(val message: String, val throwable: Throwable?)

  /** Keeps the warnings and errors it is given. */
  private class Capture : Logger {
    val records = mutableListOf<Record>()
    override fun v(message: String) {}
    override fun d(message: String) {}
    override fun i(message: String) {}
    override fun w(message: String, throwable: Throwable?) {
      records += Record(message, throwable)
    }
    override fun e(message: String, throwable: Throwable?) {
      records += Record(message, throwable)
    }
  }

  @Test
  fun connect_refused_logsWhyWithoutAStackTrace() {
    // A port nothing listens on any more refuses the connection.
    val loopback = InetAddress.getLoopbackAddress()
    val port = ServerSocket(0, 1, loopback).use { it.localPort }
    val capture = Capture()
    KetchLogger.setLogger(capture)
    try {
      assertFailsWith<KetchError.Network> {
        runBlocking { RealFtpClient(loopback.hostAddress, port).connect() }
      }
    } finally {
      KetchLogger.setLogger(Logger.None)
    }

    // The console prints a record's stack trace after it, as the CLI shows a failed download.
    val record = capture.records.single()
    assertNull(record.throwable)
    assertTrue("ConnectException" in record.message, record.message)
  }
}
