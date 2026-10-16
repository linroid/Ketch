package com.linroid.ketch.cli

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Checks the client against the framing of the desktop app's `SingleInstance`. */
class DesktopAppTest {
  private val dir: File = createTempDirectory("ketch-app").toFile()

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun `request sends the token and the message and returns the reply`() {
    val received = mutableListOf<Any>()
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      File(dir, "app.endpoint").writeText("${server.localPort}\nsecret")
      val app = thread {
        server.accept().use { socket ->
          val input = DataInputStream(socket.getInputStream())
          received += input.readUTF()
          received += input.readInt()
          received += input.readUTF()
          DataOutputStream(socket.getOutputStream()).writeUTF("""{"url":"x"}""")
        }
      }

      val reply = DesktopApp.request(dir, DesktopApp.CONNECT_REQUEST)
      app.join(5_000)

      assertEquals(listOf<Any>("secret", -1, "cli/connect"), received)
      assertEquals(DesktopApp.Reply.Text("""{"url":"x"}"""), reply)
    }
  }

  @Test
  fun `an empty reply means the app runs but has no answer`() {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      File(dir, "app.endpoint").writeText("${server.localPort}\nsecret")
      val app = thread {
        server.accept().use { socket ->
          val input = DataInputStream(socket.getInputStream())
          input.readUTF()
          input.readInt()
          input.readUTF()
          DataOutputStream(socket.getOutputStream()).writeUTF("")
        }
      }

      assertTrue(DesktopApp.isRunning(dir))
      app.join(5_000)
    }
  }

  @Test
  fun `no app runs without an endpoint or with one nothing answers`() {
    assertEquals(DesktopApp.Reply.NotRunning, DesktopApp.request(dir, "cli/connect"))

    val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    File(dir, "app.endpoint").writeText("$port\nsecret")
    assertFalse(DesktopApp.isRunning(dir))
  }
}
