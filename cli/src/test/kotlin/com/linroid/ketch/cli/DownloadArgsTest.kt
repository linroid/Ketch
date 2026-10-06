package com.linroid.ketch.cli

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.SpeedLimit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DownloadArgsTest {

  @Test
  fun parseDownloadArgs_withoutQueueOverride_usesSharedDownloadDefault() {
    val parsed = parseDownloadArgs(listOf("https://example.com/a.zip"))

    assertEquals(
      DownloadConfig.Default.maxConcurrentDownloads,
      assertIs<DownloadArgs.Download>(parsed).maxConcurrent,
    )
  }

  @Test
  fun `help flags print usage instead of downloading`() {
    assertEquals(DownloadArgs.Help, parseDownloadArgs(listOf("--help")))
    assertEquals(DownloadArgs.Help, parseDownloadArgs(listOf("-h")))
    assertEquals(DownloadArgs.Help, parseDownloadArgs(listOf("https://example.com/a.zip", "-h")))
  }

  @Test
  fun `options url and destination are parsed in any order`() {
    val parsed = parseDownloadArgs(
      listOf(
        "--speed-limit", "1m", "https://example.com/a.zip", "--priority", "HIGH", "a.zip",
        "--max-concurrent", "5",
      )
    )
    assertEquals(
      DownloadArgs.Download(
        url = "https://example.com/a.zip",
        destination = "a.zip",
        speedLimit = SpeedLimit.parse("1m")!!,
        priority = DownloadPriority.HIGH,
        maxConcurrent = 5,
      ),
      parsed,
    )
  }

  @Test
  fun `unknown options are rejected rather than downloaded as a url`() {
    val parsed = parseDownloadArgs(listOf("--version"))
    assertEquals(DownloadArgs.Invalid("unknown option '--version'"), parsed)
  }

  @Test
  fun `option without a value is rejected`() {
    val parsed = parseDownloadArgs(listOf("https://example.com/a.zip", "--speed-limit"))
    assertEquals(DownloadArgs.Invalid("--speed-limit requires a value"), parsed)
  }

  @Test
  fun `header options become request headers and later ones win`() {
    val parsed = parseDownloadArgs(
      listOf(
        "-H", "Cookie: sid=1", "--header", "X-Token:abc", "--user-agent", "curl/8",
        "-H", "user-agent: Browser/1.0", "--referer", "https://example.com/page",
        "https://example.com/a.zip",
      )
    )
    assertEquals(
      mapOf(
        "Cookie" to "sid=1",
        "X-Token" to "abc",
        "user-agent" to "Browser/1.0",
        "Referer" to "https://example.com/page",
      ),
      assertIs<DownloadArgs.Download>(parsed).headers,
    )
  }

  @Test
  fun `headers that cannot be sent are rejected`() {
    val url = "https://example.com/a.zip"
    assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf(url, "-H", "Cookie sid=1")))
    assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf(url, "-H", ": value")))
    assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf(url, "-H", "X Token: abc")))
    assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf(url, "--referer", "https://a\r\nX: 1")))
  }

  @Test
  fun `invalid option values are rejected`() {
    val url = "https://example.com/a.zip"
    assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf(url, "--speed-limit", "fast")))
    assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf(url, "--priority", "asap")))
    assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf(url, "--max-concurrent", "x")))
    assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf(url, "--max-concurrent", "0")))
  }

  @Test
  fun `a third positional argument is rejected`() {
    val parsed = parseDownloadArgs(listOf("https://example.com/a", "a.zip", "b.zip"))
    assertEquals(DownloadArgs.Invalid("unexpected argument 'b.zip'"), parsed)
  }

  @Test
  fun `options without a url are rejected`() {
    val parsed = parseDownloadArgs(listOf("--priority", "high"))
    assertEquals(DownloadArgs.Invalid("missing <url>"), parsed)
  }

  @Test
  fun `no destination saves to the current directory`() {
    assertEquals(Destination("./"), resolveDestination(null, isDirectory = { false }))
  }

  @Test
  fun `bare file name resolves against the current directory`() {
    assertEquals(Destination("./file.zip"), resolveDestination("file.zip", isDirectory = { false }))
  }

  @Test
  fun `existing directory gets a trailing separator`() {
    val destination = resolveDestination("downloads", isDirectory = { it == "downloads" })
    assertEquals(Destination("downloads" + File.separatorChar), destination)
  }

  @Test
  fun `paths with a directory component are kept as given`() {
    val noDirectories: (String) -> Boolean = { false }
    assertEquals(Destination("out/file.zip"), resolveDestination("out/file.zip", noDirectories))
    assertEquals(Destination("/tmp/file.zip"), resolveDestination("/tmp/file.zip", noDirectories))
    assertEquals(Destination("new-dir/"), resolveDestination("new-dir/", noDirectories))
  }

  @Test
  fun toRequest_parsedArgs_tagsTheRequestWithTheCliOrigin() {
    val args = DownloadArgs.Download(
      url = "https://example.com/a.zip",
      destination = "a.zip",
      speedLimit = SpeedLimit.parse("1m")!!,
      priority = DownloadPriority.HIGH,
    )

    val request = args.toRequest(Destination("./a.zip"))

    assertEquals(mapOf("ketch.origin" to "cli"), request.properties)
    assertEquals(emptyMap(), request.headers)
    assertEquals("https://example.com/a.zip", request.url)
    assertEquals(Destination("./a.zip"), request.destination)
    assertEquals(SpeedLimit.parse("1m"), request.speedLimit)
    assertEquals(DownloadPriority.HIGH, request.priority)
  }
}
