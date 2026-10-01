package com.linroid.ketch.app.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurlParserTest {
  @Test
  fun parse_chromeBashCopy_readsLinkAndHeaders() {
    val command = """
      curl 'https://example.com/files/report.pdf?sig=a%2Fb' \
        -H 'accept: text/html,application/xhtml+xml' \
        -H 'accept-encoding: gzip, deflate, br' \
        -b 'session=abc; theme=dark' \
        -H 'if-none-match: "v1"' \
        -H 'range: bytes=0-' \
        -H 'referer: https://example.com/files' \
        -H 'sec-ch-ua: "Chromium";v="128"' \
        -H 'user-agent: Mozilla/5.0 (Macintosh)' \
        --compressed
    """.trimIndent()

    assertEquals(
      CurlCommand(
        urls = listOf("https://example.com/files/report.pdf?sig=a%2Fb"),
        headers = mapOf(
          "accept" to "text/html,application/xhtml+xml",
          "Cookie" to "session=abc; theme=dark",
          "referer" to "https://example.com/files",
          "sec-ch-ua" to "\"Chromium\";v=\"128\"",
          "user-agent" to "Mozilla/5.0 (Macintosh)",
        ),
      ),
      CurlParser.parse(command),
    )
  }

  @Test
  fun parse_chromeCmdCopy_undoesCaretEscapes() {
    val command = "curl ^\"https://example.com/get?a=1^&b=^%^20x^\" ^\n" +
      "  -H ^\"sec-ch-ua: \\^\"Chromium\\^\";v=\\^\"128\\^\"^\" ^\n" +
      "  -b ^\"session=abc^\" ^\n" +
      "  --compressed"

    assertEquals(
      CurlCommand(
        urls = listOf("https://example.com/get?a=1&b=%20x"),
        headers = mapOf(
          "sec-ch-ua" to "\"Chromium\";v=\"128\"",
          "Cookie" to "session=abc",
        ),
      ),
      CurlParser.parse(command),
    )
  }

  @Test
  fun parse_cmdWithPlainQuotes_keepsCaretsInsideThem() {
    assertEquals(
      listOf("https://example.com/a^b"),
      CurlParser.parse("curl \"https://example.com/a^b\" ^\n -A x")?.urls,
    )
  }

  @Test
  fun parse_ansiCQuoting_decodesEscapes() {
    val command = "curl \$'https://example.com/a\\u0021b' " +
      "-H \$'Cookie: name=it\\'s\\x20ok\\101\\U0001F600' https://example.com/c"

    val parsed = CurlParser.parse(command)

    assertEquals(listOf("https://example.com/a!b", "https://example.com/c"), parsed?.urls)
    assertEquals(mapOf("Cookie" to "name=it's okA\uD83D\uDE00"), parsed?.headers)
  }

  @Test
  fun parse_doubleQuotesAndBackslashes_followShellRules() {
    val command = "curl \"https://example.com/\\\$x\\q\" -H \"X-Note: say \\\"hi\\\"\" " +
      "https://example.com/two\\ words"

    val parsed = CurlParser.parse(command)

    assertEquals(listOf("https://example.com/\$x\\q"), parsed?.urls)
    assertEquals(mapOf("X-Note" to "say \"hi\""), parsed?.headers)
  }

  @Test
  fun parse_shortOptionClusters_readAttachedValues() {
    val command = "curl -sSLo out.zip -HCookie:a=b -A 'Ketch/1.0' -e 'https://ref.org/;auto' " +
      "https://example.com/a.zip"

    assertEquals(
      CurlCommand(
        urls = listOf("https://example.com/a.zip"),
        headers = mapOf(
          "Cookie" to "a=b",
          "User-Agent" to "Ketch/1.0",
          "Referer" to "https://ref.org/",
        ),
      ),
      CurlParser.parse(command),
    )
  }

  @Test
  fun parse_longOptionNames_matchTheShortOnes() {
    val command = "curl --header 'X-Token: 1' --cookie a=b --referer https://r.org " +
      "--user-agent UA --url https://example.com/a.zip"

    assertEquals(
      CurlCommand(
        urls = listOf("https://example.com/a.zip"),
        headers = mapOf(
          "X-Token" to "1",
          "Cookie" to "a=b",
          "Referer" to "https://r.org",
          "User-Agent" to "UA",
        ),
      ),
      CurlParser.parse(command),
    )
  }

  @Test
  fun parse_optionValues_areNotTakenForLinks() {
    val command = "curl -X GET -o file.zip --data-raw '{\"a\":1}' --proxy http://proxy.lan:8080 " +
      "-u user:pass --connect-timeout 10 -w '%{http_code}' --unknown-flag " +
      "https://example.com/a.zip | jq ."

    assertEquals(listOf("https://example.com/a.zip"), CurlParser.parse(command)?.urls)
  }

  @Test
  fun parse_repeatedHeaders_replaceIgnoringCaseAndJoinCookies() {
    val command = "curl -H 'Accept: a' -H 'accept: b' -H 'Cookie: x=1' -b 'y=2' -H 'cookie: z=3' " +
      "https://example.com/a"

    assertEquals(
      mapOf("accept" to "b", "Cookie" to "x=1; y=2; z=3"),
      CurlParser.parse(command)?.headers,
    )
  }

  @Test
  fun parse_headersCurlDoesNotSend_areLeftOut() {
    val command = "curl -b cookies.txt -H @headers.txt -H 'X-Empty;' -H 'X-Removed:' " +
      "-H ':authority: example.com' -H 'Host: example.com' -e ';auto' -A '' https://example.com/a"

    assertEquals(emptyMap(), CurlParser.parse(command)?.headers)
  }

  @Test
  fun parse_linkWithoutScheme_addsHttps() {
    assertEquals(
      listOf("https://example.com/file.iso", "https://example.com"),
      CurlParser.parse("curl example.com/file.iso example.com")?.urls,
    )
  }

  @Test
  fun parse_afterDoubleDash_readsEverythingAsLinks() {
    assertEquals(
      listOf("https://example.com/-a"),
      CurlParser.parse("curl -- -H https://example.com/-a")?.urls,
    )
  }

  @Test
  fun parse_globOff_isReported() {
    assertTrue(CurlParser.parse("curl -g https://x.org/[1-2]")?.globOff == true)
    assertTrue(CurlParser.parse("curl -sg https://x.org/[1-2]")?.globOff == true)
    assertTrue(CurlParser.parse("curl --globoff https://x.org/[1-2]")?.globOff == true)
    assertFalse(CurlParser.parse("curl https://x.org/[1-2]")?.globOff == true)
  }

  @Test
  fun parse_shellPromptAndExe_areAccepted() {
    listOf("$ curl https://x.org/a", "curl.exe https://x.org/a", "  CURL https://x.org/a")
      .forEach { assertEquals(listOf("https://x.org/a"), CurlParser.parse(it)?.urls, it) }
  }

  @Test
  fun parse_malformedCommand_returnsNull() {
    listOf(
      "curl",
      "curl -sSL",
      "curl https://x.org/a -H",
      "curl https://x.org/a --url",
      "curl https://x.org/a -o",
      "curl 'https://x.org/a",
      "curl \"https://x.org/a",
      "curl \$'https://x.org/a",
      "curl https://x.org/a \\",
      "curl ^\"https://x.org/a^\" ^",
      "curl ^\"https://x.org/a",
      "curl is a command line tool",
      "curly https://x.org/a",
      "wget https://x.org/a",
      "echo curl https://x.org/a",
    ).forEach { assertNull(CurlParser.parse(it), it) }
  }

  @Test
  fun isIncomplete_openQuoteOrContinuation_needsMoreLines() {
    assertTrue(CurlParser.isIncomplete("curl 'https://x.org/a"))
    assertTrue(CurlParser.isIncomplete("curl https://x.org/a \\"))
    assertTrue(CurlParser.isIncomplete("curl ^\"https://x.org/a^\" ^"))
    assertFalse(CurlParser.isIncomplete("curl 'https://x.org/a' \\\n  -H 'A: b'"))
    assertFalse(CurlParser.isIncomplete("curl https://x.org/a"))
  }

  @Test
  fun isCurl_commandStart_isRecognized() {
    assertTrue(CurlParser.isCurl("curl https://x.org"))
    assertTrue(CurlParser.isCurl("\$ curl -L x"))
    assertTrue(CurlParser.isCurl("curl"))
    assertFalse(CurlParser.isCurl("curly fries"))
    assertFalse(CurlParser.isCurl("see curl https://x.org"))
  }
}
