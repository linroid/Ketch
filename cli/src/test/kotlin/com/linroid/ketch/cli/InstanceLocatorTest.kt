package com.linroid.ketch.cli

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class InstanceLocatorTest {
  private val dir: File = createTempDirectory("ketch-locator").toFile()

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun `a server address comes first, with the token given`() {
    val locator = locator(server = "https://ketch.example.com", token = "secret") {
      error("The app must not be asked")
    }

    val endpoint = locator.locate()

    assertEquals("ketch.example.com", endpoint.host)
    assertEquals(443, endpoint.port)
    assertEquals(true, endpoint.secure)
    assertEquals("secret", endpoint.token)
    assertEquals(false, endpoint.local)
  }

  @Test
  fun `the running app answers with its loopback server`() {
    val endpoint = locator { DesktopApp.Reply.Text("""{"url":"http://127.0.0.1:5123","token":"t"}""") }
      .locate()

    assertEquals(
      InstanceEndpoint("the Ketch app", "127.0.0.1", 5123, false, "t", local = true),
      endpoint,
    )
  }

  @Test
  fun `the app is asked again while it is still starting`() {
    var asked = 0
    val endpoint = locator {
      if (++asked < 3) {
        DesktopApp.Reply.Text("""{"error":"not_ready","message":"Ketch is still starting"}""")
      } else {
        DesktopApp.Reply.Text("""{"url":"http://127.0.0.1:5123","token":"t"}""")
      }
    }.locate()

    assertEquals(3, asked)
    assertEquals(5123, endpoint.port)
  }

  @Test
  fun `an app that cannot start its server is a failure`() {
    val failure = assertFailsWith<CliFailure> {
      locator {
        DesktopApp.Reply.Text("""{"error":"server_failed","message":"port in use"}""")
      }.locate()
    }

    assertEquals("The Ketch app couldn't take the connection: port in use", failure.message)
  }

  @Test
  fun `an app without the command line request asks to be updated`() {
    val failure = assertFailsWith<CliFailure> { locator { DesktopApp.Reply.Empty }.locate() }

    assertTrue("update it" in failure.message.orEmpty())
  }

  @Test
  fun `without the app the running ketch server is used`() {
    publish(CliInstance.Info("ketch server", pid = 41, url = "http://127.0.0.1:8642", token = "s"))

    val endpoint = locator(alive = setOf(41L)) { DesktopApp.Reply.NotRunning }.locate()

    assertEquals(
      InstanceEndpoint("ketch server", "127.0.0.1", 8642, false, "s", local = true),
      endpoint,
    )
  }

  @Test
  fun `a ketch server that stopped without cleaning up is not used`() {
    publish(CliInstance.Info("ketch server", pid = 41, url = "http://127.0.0.1:8642"))

    val failure = assertFailsWith<CliFailure> {
      locator(alive = emptySet()) { DesktopApp.Reply.NotRunning }.locate()
    }

    assertTrue(failure.message.orEmpty().startsWith("Ketch isn't running"))
  }

  @Test
  fun `the running ketch server's own address gets its token`() {
    publish(CliInstance.Info("ketch server", pid = 41, url = "http://127.0.0.1:8642", token = "s"))

    val own = locator(server = "127.0.0.1:8642") { error("not asked") }.locate()
    val other = locator(server = "127.0.0.1:9000") { error("not asked") }.locate()

    assertEquals("s", own.token)
    assertEquals(null, other.token)
  }

  @Test
  fun `parseServerAddress takes URLs and host and port`() {
    assertEquals(
      InstanceEndpoint("nas:8642", "nas", 8642, false, null, local = false),
      parseServerAddress("nas", token = " "),
    )
    assertEquals(9000, parseServerAddress("http://192.168.1.20:9000", null).port)
    val ipv6 = parseServerAddress("http://[::1]:8642/", null)
    assertEquals("[::1]", ipv6.host)
    assertEquals(true, ipv6.local)
    assertEquals(true, parseServerAddress("localhost:8642", null).local)
    assertEquals(true, parseServerAddress("127.0.0.2", null).local)
    assertEquals(false, parseServerAddress("beef.cafe", null).local)
  }

  @Test
  fun `parseServerAddress refuses other schemes and paths`() {
    assertFailsWith<CliFailure> { parseServerAddress("ftp://nas", null) }
    assertFailsWith<CliFailure> { parseServerAddress("http://nas:8642/api", null) }
    assertFailsWith<CliFailure> { parseServerAddress("http://", null) }
  }

  private fun publish(info: CliInstance.Info) {
    Files.writeString(File(dir, CliInstance.INFO_FILE).toPath(), info.toJson().toString())
  }

  private fun locator(
    server: String? = null,
    token: String? = null,
    alive: Set<Long> = emptySet(),
    app: () -> DesktopApp.Reply,
  ) = InstanceLocator(
    configDir = dir,
    server = server,
    token = token,
    askApp = { _, message ->
      assertEquals(DesktopApp.CONNECT_REQUEST, message)
      app()
    },
    isAlive = { it in alive },
    appStartTimeout = 5_000.milliseconds,
    retryInterval = 1.milliseconds,
  )
}
