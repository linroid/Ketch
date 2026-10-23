package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RealFtpClientSizeTest {
  private suspend fun ScriptedFtpServer.size(path: String): Long? {
    val client = RealFtpClient(host = "localhost", port = port)
    try {
      client.connect()
      client.login("anonymous", "guest")
      client.setBinaryMode()
      return client.size(path)
    } finally {
      client.disconnect()
    }
  }

  @Test
  fun size_missingPath_saysTheServerHasNoFileThere() = runTest {
    ScriptedFtpServer(
      ByteArray(0),
      sizeReply = "550 Could not get file size.",
      mdtmReply = "550 Could not get file modification time.",
    ).use { server ->
      val error = assertFailsWith<KetchError.SourceError> { server.size("/pub/missing.iso") }

      assertEquals(
        "The server has no file at /pub/missing.iso (FTP 550: Could not get file size.)",
        error.detail,
      )
    }
  }

  @Test
  fun size_fileTheServerCannotSize_isUnknown() = runTest {
    ScriptedFtpServer(
      ByteArray(0),
      sizeReply = "550 SIZE not allowed for this file",
      mdtmReply = "213 20250101000000",
    ).use { server ->
      assertNull(server.size("/pub/file.iso"))
    }
  }

  @Test
  fun size_serverWithoutSize_isUnknown() = runTest {
    ScriptedFtpServer(ByteArray(0)).use { server ->
      assertNull(server.size("/pub/file.iso"))
    }
  }
}
