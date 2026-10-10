package com.linroid.ketch.cli

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.torrent.TorrentFileOrder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

  @Test
  fun `proxy option sets the request proxy with its credentials and bypass list`() {
    val args = parseDownloadArgs(
      listOf("--proxy", "socks5://me:secret@127.0.0.1:1080", "--proxy-bypass", "*.lan, 10.0.0.0/8",
        "https://example.com/a.zip"),
    )
    val download = assertIs<DownloadArgs.Download>(args)
    val expected = ProxyConfig.manual(
      "socks5://me:secret@127.0.0.1:1080",
      bypass = listOf("*.lan", "10.0.0.0/8"),
    )
    assertEquals(expected, download.proxy)
    assertEquals(expected, download.toRequest(Destination("./")).proxy)
  }

  @Test
  fun `no proxy option connects directly`() {
    val args = parseDownloadArgs(listOf("--no-proxy", "https://example.com/a.zip"))
    assertEquals(ProxyConfig.Direct, assertIs<DownloadArgs.Download>(args).proxy)
  }

  @Test
  fun `without proxy options the configured proxy applies`() {
    val args = parseDownloadArgs(listOf("https://example.com/a.zip"))
    assertEquals(null, assertIs<DownloadArgs.Download>(args).proxy)
  }

  @Test
  fun `invalid or conflicting proxy options are rejected`() {
    for (options in listOf(
      listOf("--proxy", "https://proxy.example"),
      listOf("--proxy", "http://p:8080", "--no-proxy"),
      listOf("--proxy-bypass", "*.lan"),
      listOf("--proxy", "http://p:8080", "--proxy-bypass", "a b"),
    )) {
      val args = parseDownloadArgs(options + "https://example.com/a")
      assertIs<DownloadArgs.Invalid>(args, "$options")
    }
  }

  @Test
  fun parse_files_buildsTheSelection() {
    val parsed = parseDownloadArgs(listOf(MAGNET, "--files", "3, 0,3"))

    val download = assertIs<DownloadArgs.Download>(parsed)
    assertEquals(setOf("3", "0"), download.fileIds)
    assertEquals(setOf("3", "0"), download.toRequest(Destination("./")).selectedFileIds)
  }

  @Test
  fun parse_withoutFiles_downloadsEveryFile() {
    val download = assertIs<DownloadArgs.Download>(parseDownloadArgs(listOf(MAGNET)))

    assertEquals(emptySet(), download.toRequest(Destination("./")).selectedFileIds)
    assertFalse(download.listFiles)
  }

  @Test
  fun parse_emptyFiles_isInvalid() {
    for (value in listOf("", " ", "1,,2", "1,")) {
      assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf(MAGNET, "--files", value)), value)
    }
    assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf(MAGNET, "--files")))
  }

  @Test
  fun parse_listFiles_setsTheMode() {
    val download = assertIs<DownloadArgs.Download>(
      parseDownloadArgs(listOf("--list-files", MAGNET)),
    )

    assertTrue(download.listFiles)
    assertEquals(TorrentFileOrder.TORRENT, download.fileOrder)
    assertFalse(download.reverse)
  }

  @Test
  fun parse_listFilesWithFiles_isInvalid() {
    val parsed = parseDownloadArgs(listOf("--list-files", "--files", "1", MAGNET))

    assertEquals(
      DownloadArgs.Invalid("--list-files and --files cannot be used together"),
      parsed,
    )
  }

  @Test
  fun parse_listFilesSortAndReverse_setTheOrder() {
    val orders = mapOf(
      "name" to TorrentFileOrder.NAME,
      "SIZE" to TorrentFileOrder.SIZE,
      "type" to TorrentFileOrder.EXTENSION,
    )
    for ((value, order) in orders) {
      val download = assertIs<DownloadArgs.Download>(
        parseDownloadArgs(listOf("--list-files", "--sort", value, "--reverse", MAGNET)),
      )
      assertEquals(order, download.fileOrder, value)
      assertTrue(download.reverse)
    }
  }

  @Test
  fun parse_unknownSortOrSortWithoutListFiles_isInvalid() {
    assertEquals(
      DownloadArgs.Invalid("invalid sort 'kind' (valid values: name, size, type)"),
      parseDownloadArgs(listOf("--list-files", "--sort", "kind", MAGNET)),
    )
    assertEquals(
      DownloadArgs.Invalid("--sort and --reverse require --list-files"),
      parseDownloadArgs(listOf("--sort", "size", MAGNET)),
    )
    assertIs<DownloadArgs.Invalid>(parseDownloadArgs(listOf("--reverse", MAGNET)))
  }

  @Test
  fun fileListRows_sortedBySize_printsAlignedRowsLargestFirst() {
    val rows = fileListRows(torrent(), TorrentFileOrder.SIZE, reverse = true)

    // Sizes follow the default locale's decimal separator.
    val sizes = listOf(formatBytes(1_048_576), formatBytes(204_800), formatBytes(100))
    val width = sizes.maxOf { it.length }
    assertEquals(
      listOf(
        "ID  ${"SIZE".padStart(width)}  PATH",
        "2   ${sizes[0].padStart(width)}  Pack/Episode 10.mkv",
        "0   ${sizes[1].padStart(width)}  Pack/Episode 2.mkv",
        "1   ${sizes[2].padStart(width)}  Pack/notes.txt",
      ),
      rows,
    )
  }

  @Test
  fun fileListRows_byNameOrType_usesNaturalAndExtensionOrder() {
    assertEquals(
      listOf("0", "2", "1"),
      fileListRows(torrent(), TorrentFileOrder.NAME).drop(1).map { it.substringBefore(' ') },
    )
    assertEquals(
      listOf("0", "2", "1"),
      fileListRows(torrent(), TorrentFileOrder.EXTENSION).drop(1).map { it.substringBefore(' ') },
    )
    assertEquals(
      listOf("0", "1", "2"),
      fileListRows(torrent()).drop(1).map { it.substringBefore(' ') },
    )
  }

  @Test
  fun fileListRows_pathWithControlCharacters_printsOneLine() {
    val source = torrent().copy(files = listOf(SourceFile("0", "a\nb\u001b[2Jc.txt", 1)))

    val row = fileListRows(source).last()

    assertFalse(row.any { it == '\n' || it == '\u001b' }, row)
  }

  @Test
  fun fileListRows_singleFileSource_isEmpty() {
    assertEquals(emptyList(), fileListRows(torrent().copy(files = emptyList())))
  }

  private fun torrent() = ResolvedSource(
    url = MAGNET,
    sourceType = "torrent",
    totalBytes = 1_249_700,
    supportsResume = true,
    suggestedFileName = "Pack",
    maxSegments = 1,
    files = listOf(
      SourceFile("0", "Pack/Episode 2.mkv", 204_800, mapOf("path" to "Pack/Episode 2.mkv")),
      SourceFile("1", "Pack/notes.txt", 100, mapOf("path" to "Pack/notes.txt")),
      SourceFile("2", "Pack/Episode 10.mkv", 1_048_576, mapOf("path" to "Pack/Episode 10.mkv")),
    ),
  )
}

private const val MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
