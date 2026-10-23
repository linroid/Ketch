package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RealFtpClientPassiveTest {
  private val content = ByteArray(64 * 1024) { (it * 31 % 251).toByte() }

  private suspend fun RealFtpClient.download(): ByteArray {
    val received = mutableListOf<Byte>()
    retrieve("/file.bin") { received += it.toList() }
    return received.toByteArray()
  }

  private suspend fun ScriptedFtpServer.downloadTwice(): List<ByteArray> {
    val client = RealFtpClient(host = "localhost", port = port)
    try {
      client.connect()
      client.login("anonymous", "guest")
      client.setBinaryMode()
      return listOf(client.download(), client.download())
    } finally {
      client.disconnect()
    }
  }

  @Test
  fun retrieve_unreachableEpsvPort_fallsBackToPasv() = runTest {
    ScriptedFtpServer(content, epsvUnreachable = true).use { server ->
      server.downloadTwice().forEach { assertContentEquals(content, it) }

      // The connection skips EPSV once it failed.
      val passive = server.commands.filter { it == "EPSV" || it == "PASV" }
      assertEquals(listOf("EPSV", "PASV", "PASV"), passive)
    }
  }

  @Test
  fun retrieve_pasvNamingAnotherAddress_connectsToControlHost() = runTest {
    // TEST-NET-1 is never routed: a client dialing it would time out instead of downloading.
    ScriptedFtpServer(content, epsvUnreachable = true, pasvAddress = "192,0,2,1").use { server ->
      server.downloadTwice().forEach { assertContentEquals(content, it) }
    }
  }

  @Test
  fun retrieve_transferEndingWithRefusal_throwsTheReplysError() = runTest {
    val refusal = "425 Cannot secure data connection - TLS session resumption required."
    ScriptedFtpServer(content, refusedTransferReply = refusal).use { server ->
      val client = RealFtpClient(host = "localhost", port = server.port)
      try {
        client.connect()
        client.login("anonymous", "guest")
        client.setBinaryMode()

        assertFailsWith<KetchError.Unsupported> { client.download() }
      } finally {
        client.disconnect()
      }
    }
  }
}
