package com.linroid.ketch.app.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

class NativeMessagingHostTest {
  private val reply = """{"url":"http://127.0.0.1:5000","token":"t"}"""

  @Test
  fun nativeMessage_roundTripsWithANativeOrderLengthPrefix() {
    val output = ByteArrayOutputStream()
    writeNativeMessage(output, """{"type":"connect","name":"ü"}""")

    val bytes = output.toByteArray()
    val length = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.nativeOrder()).int
    assertEquals(bytes.size - 4, length)
    assertEquals(
      """{"type":"connect","name":"ü"}""",
      readNativeMessage(ByteArrayInputStream(bytes)),
    )
  }

  @Test
  fun readNativeMessage_fromAPipe_readsTheWholeMessage() {
    // Browsers connect stdin to a pipe, where FileInputStream.readNBytes fails trying to seek.
    if (DesktopOs.current == DesktopOs.WINDOWS) return
    val dir = Files.createTempDirectory("ketch-pipe").toFile()
    try {
      val pipe = File(dir, "stdin")
      assertEquals(0, ProcessBuilder("mkfifo", pipe.path).start().waitFor())
      val writer = thread {
        FileOutputStream(pipe).use { writeNativeMessage(it, """{"type":"connect"}""") }
      }
      FileInputStream(pipe).use { input ->
        assertEquals("""{"type":"connect"}""", readNativeMessage(input))
        assertNull(readNativeMessage(input))
      }
      writer.join()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun readNativeMessage_closedStream_returnsNull() {
    assertNull(readNativeMessage(ByteArrayInputStream(ByteArray(0))))
  }

  @Test
  fun readNativeMessage_rejectsOversizedAndTruncatedMessages() {
    val huge = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(1 shl 20).array()
    assertFailsWith<IOException> { readNativeMessage(ByteArrayInputStream(huge)) }
    val truncated = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder()).putInt(10).array()
    assertFailsWith<IOException> { readNativeMessage(ByteArrayInputStream(truncated)) }
  }

  @Test
  fun connect_appRunning_passesOnItsReplyWithoutLaunching() {
    val host = NativeMessagingHost(
      requestApp = { reply },
      launchApp = { error("Must not launch a running app") },
    )
    assertEquals(reply, host.handle("""{"type":"connect"}"""))
  }

  @Test
  fun connect_appNotRunningAndNoLaunch_saysSo() {
    var launched = false
    val host = NativeMessagingHost(requestApp = { null }, launchApp = { launched = true })

    val answer = host.handle("""{"type":"connect","launch":false}""")

    assertEquals("""{"error":"not_running","message":"Ketch isn't running"}""", answer)
    assertEquals(false, launched)
  }

  @Test
  fun connect_appNotRunning_launchesItOnceAndWaitsForItsReply() {
    var launches = 0
    var requests = 0
    val host = NativeMessagingHost(
      // The app answers on the third request, once it has started.
      requestApp = { if (++requests >= 3) reply else null },
      launchApp = { launches++ },
      pollInterval = 1.milliseconds,
    )

    assertEquals(reply, host.handle("""{"type":"connect","launch":true}"""))
    assertEquals(1, launches)
  }

  @Test
  fun connect_appStillLoading_keepsAskingUntilItIsReady() {
    val notReady = """{"error":"not_ready","message":"Ketch is still starting"}"""
    for (launch in listOf(true, false)) {
      val answers = ArrayDeque(listOf(notReady, notReady, reply))
      val host = NativeMessagingHost(
        requestApp = { answers.removeFirst() },
        launchApp = { error("Must not launch a running app") },
        pollInterval = 1.milliseconds,
      )
      assertEquals(reply, host.handle("""{"type":"connect","launch":$launch}"""))
    }
  }

  @Test
  fun connect_launchedAppLoading_waitsPastNotReady() {
    val notReady = """{"error":"not_ready","message":"Ketch is still starting"}"""
    val answers = ArrayDeque(listOf(null, null, notReady, reply))
    var launches = 0
    val host = NativeMessagingHost(
      requestApp = { answers.removeFirst() },
      launchApp = { launches++ },
      pollInterval = 1.milliseconds,
    )

    assertEquals(reply, host.handle("""{"type":"connect"}"""))
    assertEquals(1, launches)
  }

  @Test
  fun connect_appNeverFinishesLoading_repliesThatItIsStillStarting() {
    val notReady = """{"error":"not_ready","message":"Ketch is still starting"}"""
    val host = NativeMessagingHost(
      requestApp = { notReady },
      launchApp = { },
      startTimeout = 20.milliseconds,
      pollInterval = 5.milliseconds,
    )
    assertEquals(notReady, host.handle("""{"type":"connect"}"""))
  }

  @Test
  fun connect_appNeverStarts_givesUpAfterTheTimeout() {
    val host = NativeMessagingHost(
      requestApp = { null },
      launchApp = { },
      startTimeout = 20.milliseconds,
      pollInterval = 5.milliseconds,
    )
    assertEquals(
      """{"error":"not_started","message":"Ketch didn't start in time"}""",
      host.handle("""{"type":"connect"}"""),
    )
  }

  @Test
  fun connect_launchFails_reportsIt() {
    val host = NativeMessagingHost(
      requestApp = { null },
      launchApp = { throw IOException("no such file") },
    )
    assertEquals(
      """{"error":"launch_failed","message":"Couldn't start Ketch: no such file"}""",
      host.handle("""{"type":"connect"}"""),
    )
  }

  @Test
  fun handle_rejectsMalformedAndUnknownRequests() {
    val host = NativeMessagingHost(requestApp = { reply }, launchApp = { })
    assertEquals(
      """{"error":"bad_request","message":"The request is not a JSON object"}""",
      host.handle("[1]"),
    )
    assertEquals(
      """{"error":"bad_request","message":"Unknown request type"}""",
      host.handle("""{"type":"delete-everything"}"""),
    )
  }

  @Test
  fun serve_answersOneFramedRequest() {
    val input = ByteArrayOutputStream().also { writeNativeMessage(it, """{"type":"connect"}""") }
    val output = ByteArrayOutputStream()
    NativeMessagingHost(requestApp = { reply }, launchApp = { })
      .serve(ByteArrayInputStream(input.toByteArray()), output)

    assertEquals(reply, readNativeMessage(ByteArrayInputStream(output.toByteArray())))
  }
}
