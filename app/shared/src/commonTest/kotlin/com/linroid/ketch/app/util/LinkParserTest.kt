package com.linroid.ketch.app.util

import com.linroid.ketch.app.util.IntakeItem.Discover
import com.linroid.ketch.app.util.IntakeItem.Link
import com.linroid.ketch.app.util.IntakeItem.Range
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkParserTest {
  private fun parse(text: String, fileName: String? = null) = LinkParser.parseIntake(text, fileName)

  private fun urls(text: String) = parse(text).links().map { it.url }

  @Test
  fun parseIntake_oneLinkPerLine_returnsEachInOrder() {
    val text = """
      https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso
      ftp://ftp.example.org/pub/file.tar.gz
      ftps://secure.example.org/file.bin
      http://example.com/a.torrent
    """.trimIndent()

    assertEquals(
      listOf(
        Link("https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso"),
        Link("ftp://ftp.example.org/pub/file.tar.gz"),
        Link("ftps://secure.example.org/file.bin"),
        Link("http://example.com/a.torrent"),
      ),
      parse(text),
    )
  }

  @Test
  fun parseIntake_linksInProse_leavesOutSurroundingPunctuation() {
    val cases = mapOf(
      "Get it from https://example.com/a.zip." to "https://example.com/a.zip",
      "(mirror: https://example.com/a.zip)" to "https://example.com/a.zip",
      "See https://en.wikipedia.org/wiki/Foo_(bar), then" to
        "https://en.wikipedia.org/wiki/Foo_(bar)",
      "**https://example.com/a.zip**" to "https://example.com/a.zip",
      "[docs](https://example.com/a.pdf)" to "https://example.com/a.pdf",
      "[https://example.com/a.pdf](https://example.com/a.pdf)" to "https://example.com/a.pdf",
      "<https://example.com/a.zip>" to "https://example.com/a.zip",
      "\"https://example.com/a.zip\"" to "https://example.com/a.zip",
      "下载：https://example.com/a.zip。" to "https://example.com/a.zip",
      "Is it https://example.com/a.zip?" to "https://example.com/a.zip",
      "Mirrors: https://example.com/a.zip…" to "https://example.com/a.zip",
      "下载地址https://example.com/a.zip" to "https://example.com/a.zip",
      "x HTTPS://Example.com/A.zip" to "HTTPS://Example.com/A.zip",
    )
    cases.forEach { (text, url) -> assertEquals(listOf(url), urls(text), text) }
  }

  @Test
  fun parseIntake_html_readsLinksAndDecodesAmpersands() {
    val html = "<p><a href=\"https://example.com/f?a=1&amp;b=2\">" +
      "https://example.com/f?a=1&amp;b=2</a> " +
      "<a href='magnet:?xt=urn:btih:${HEX_HASH}&amp;dn=Big'>Big</a></p>"

    assertEquals(
      listOf("https://example.com/f?a=1&b=2", "magnet:?xt=urn:btih:$HEX_HASH&dn=Big"),
      urls(html),
    )
  }

  @Test
  fun parseIntake_htmlEscapedQuote_endsTheLink() {
    assertEquals(
      listOf("https://example.com/a.zip"),
      urls("href=&quot;https://example.com/a.zip&quot;"),
    )
  }

  @Test
  fun parseIntake_magnet_keepsTheWholeLink() {
    val magnet = "magnet:?xt=urn:btih:$HEX_HASH&dn=Big.Buck.Bunny" +
      "&tr=udp%3A%2F%2Ftracker.example.org%3A1337&tr=http://tracker.example.org/announce"

    assertEquals(listOf(Link(magnet)), parse("Grab this: $magnet"))
  }

  @Test
  fun parseIntake_bareInfoHash_becomesMagnet() {
    val cases = mapOf(
      HEX_HASH.uppercase() to "magnet:?xt=urn:btih:$HEX_HASH",
      "hash: $HEX_HASH." to "magnet:?xt=urn:btih:$HEX_HASH",
      BASE32_HASH.lowercase() to "magnet:?xt=urn:btih:$BASE32_HASH",
    )
    cases.forEach { (text, url) -> assertEquals(listOf(url), urls(text), text) }
  }

  @Test
  fun parseIntake_almostAnInfoHash_isNotALink() {
    listOf(HEX_HASH.drop(1), HEX_HASH + "0", HEX_HASH.dropLast(1) + "g", BASE32_HASH + "A")
      .forEach { assertIs<Discover>(parse(it).single(), it) }
  }

  @Test
  fun parseIntake_missingScheme_addsHttps() {
    val cases = mapOf(
      "releases.ubuntu.com/24.04/ubuntu.iso" to "https://releases.ubuntu.com/24.04/ubuntu.iso",
      "Get example.com/a.zip now" to "https://example.com/a.zip",
      "cdn.example.org:8443/file.bin" to "https://cdn.example.org:8443/file.bin",
      "192.168.1.20:8080/share/file.iso" to "https://192.168.1.20:8080/share/file.iso",
      "(example.com/a.zip)" to "https://example.com/a.zip",
    )
    cases.forEach { (text, url) -> assertEquals(listOf(url), urls(text), text) }
  }

  @Test
  fun parseIntake_wordsThatLookLikeHosts_areNotLinks() {
    listOf("ubuntu.iso", "e.g. this one", "v1.2/3", "/usr/share/file.iso", "bob@example.com/x")
      .forEach { assertIs<Discover>(parse(it).single(), it) }
  }

  @Test
  fun parseIntake_numericRange_expandsWithZeroPadding() {
    val cases = mapOf(
      "https://x.org/part[01-04].rar" to
        (1..4).map { "https://x.org/part0$it.rar" },
      "https://x.org/img[8-11].png" to
        listOf(8, 9, 10, 11).map { "https://x.org/img$it.png" },
      "https://x.org/v[098-101]" to
        listOf("098", "099", "100", "101").map { "https://x.org/v$it" },
      "https://x.org/[c-e].txt" to
        listOf("c", "d", "e").map { "https://x.org/$it.txt" },
    )
    cases.forEach { (pattern, expanded) ->
      val range = assertIs<Range>(parse(pattern).single(), pattern)
      assertEquals(pattern, range.pattern)
      assertEquals(expanded, range.urls)
      assertNull(range.warning)
    }
  }

  @Test
  fun parseIntake_choiceRange_expandsEachChoice() {
    val range = assertIs<Range>(parse("https://x.org/img{a,b,c}.png").single())

    assertEquals(listOf("a", "b", "c").map { "https://x.org/img$it.png" }, range.urls)
  }

  @Test
  fun parseIntake_severalRanges_expandLastFastest() {
    val range = assertIs<Range>(parse("https://x.org/s[1-2]e{a,b}.mkv").single())

    assertEquals(
      listOf("1ea", "1eb", "2ea", "2eb").map { "https://x.org/s$it.mkv" },
      range.urls,
    )
  }

  @Test
  fun parseIntake_rangePastTheCap_isCutWithAWarning() {
    val range = assertIs<Range>(parse("https://x.org/f[1-500].bin").single())

    assertEquals(MAX_EXPANDED_LINKS, range.urls.size)
    assertEquals("https://x.org/f200.bin", range.urls.last())
    assertTrue(range.truncated)
    assertEquals("Expanded to the first 200 links", range.warning)
  }

  @Test
  fun parseIntake_rangeAtTheCap_hasNoWarning() {
    val range = assertIs<Range>(parse("https://x.org/f[1-200].bin").single())

    assertEquals(200, range.urls.size)
    assertFalse(range.truncated)
    assertNull(range.warning)
  }

  @Test
  fun parseIntake_hugeRanges_expandOnlyTheFirstLinks() {
    val range = assertIs<Range>(
      parse("https://x.org/[0-999999999999999999]/[0-999999999999999999]{a,b}").single(),
    )

    assertEquals(200, range.urls.size)
    assertEquals("https://x.org/0/0a", range.urls.first())
    assertEquals("https://x.org/0/99b", range.urls.last())
    assertTrue(range.truncated)
  }

  @Test
  fun parseIntake_bracketsThatAreNotRanges_keepTheLink() {
    listOf(
      "http://[::1]:8080/file.iso",
      "https://x.org/part[12-01].rar",
      "https://x.org/{id}/file.iso",
      "https://x.org/part%5B01-02%5D.rar",
      "https://x.org/[a-Z].txt",
      "https://x.org/[1-1234567890123456789].txt",
      "magnet:?xt=urn:btih:$HEX_HASH&dn=Show[01-02]",
    ).forEach { assertEquals(listOf(Link(it)), parse(it), it) }
  }

  @Test
  fun parseIntake_rangeWithoutScheme_addsHttpsAndExpands() {
    val range = assertIs<Range>(parse("cdn.example.org/set/part[1-2].rar").single())

    assertEquals("https://cdn.example.org/set/part[1-2].rar", range.pattern)
    assertEquals(2, range.urls.size)
  }

  @Test
  fun parseIntake_repeatedLinks_keepsTheFirst() {
    val text = """
      https://example.com/a.zip
      HTTPS://EXAMPLE.COM:443/a.zip#top
      magnet:?xt=urn:btih:$HEX_HASH&dn=First
      $HEX_HASH
      magnet:?xt=urn:btih:$HEX_HASH&dn=Second
    """.trimIndent()

    assertEquals(
      listOf("https://example.com/a.zip", "magnet:?xt=urn:btih:$HEX_HASH&dn=First"),
      urls(text),
    )
  }

  @Test
  fun parseIntake_linksJoinedByCommas_areSplit() {
    assertEquals(
      listOf("https://a.org/1.zip", "https://b.org/2.zip", "magnet:?xt=urn:btih:$HEX_HASH"),
      urls("https://a.org/1.zip,https://b.org/2.zip;magnet:?xt=urn:btih:$HEX_HASH"),
    )
    // A comma inside a link stays.
    assertEquals(listOf("https://a.org/x,y.zip"), urls("https://a.org/x,y.zip"))
  }

  @Test
  fun parseIntake_linkWithoutHost_isLeftOut() {
    assertEquals(listOf("https://ok.org/a"), urls("https:///nohost https://ok.org/a ftp://:21/x"))
  }

  @Test
  fun parseIntake_lineWithAnotherScheme_isKeptForTheDeviceToReject() {
    val link = "ed2k://|file|ubuntu.iso|5700000000|0123456789ABCDEF|/"

    assertEquals(listOf(Link(link)), parse("  $link  "))
    assertEquals(LinkKind.Other, Link(link).kind)
    assertIs<Discover>(parse("open chrome://settings please").single())
  }

  @Test
  fun parseIntake_schemeEndingInASupportedOne_keepsItsOwnScheme() {
    val link = "sftp://host.example.org/file.iso"

    assertEquals(listOf(Link(link)), parse(link))
    assertIs<Discover>(parse("get $link now").single())
    assertEquals(
      listOf("https://example.com/a.zip"),
      urls("git+https://example.com/repo.git or https://example.com/a.zip"),
    )
  }

  @Test
  fun parseIntake_textWithoutLinks_becomesADiscoverSearch() {
    assertEquals(
      listOf(Discover("ubuntu 24.04 desktop iso")),
      parse("  ubuntu  24.04\n desktop iso "),
    )
    assertEquals(emptyList(), parse(" \n\t "))
  }

  @Test
  fun parseIntake_linkFileWithoutLinks_yieldsNothing() {
    assertEquals(emptyList(), parse("just some notes", fileName = "links.txt"))
  }

  @Test
  fun parseIntake_textFile_readsOneLinkPerLine() {
    val text = "# mirrors\r\nhttps://a.org/1.zip\r\n\r\nhttps://b.org/2.zip\r\n"

    assertEquals(
      listOf("https://a.org/1.zip", "https://b.org/2.zip"),
      parse(text, fileName = "list.TXT").links().map { it.url },
    )
  }

  @Test
  fun parseIntake_csvFile_readsEachCell() {
    val csv = """
      name,url,size
      ubuntu,https://a.org/u.iso,5.7 GB
      "Debian, netinst","https://b.org/d,1.iso";https://c.org/w.iso
    """.trimIndent()

    assertEquals(
      listOf("https://a.org/u.iso", "https://b.org/d,1.iso", "https://c.org/w.iso"),
      parse(csv, fileName = "export.csv").links().map { it.url },
    )
  }

  @Test
  fun parseIntake_curlCommand_keepsItsHeaders() {
    val text = """
      curl 'https://example.com/report.pdf' \
        -H 'cookie: session=abc' \
        -H 'referer: https://example.com/files'
      https://example.com/other.zip
    """.trimIndent()

    assertEquals(
      listOf(
        Link(
          "https://example.com/report.pdf",
          mapOf("cookie" to "session=abc", "referer" to "https://example.com/files"),
        ),
        Link("https://example.com/other.zip"),
      ),
      parse(text),
    )
  }

  @Test
  fun parseIntake_curlWithRange_expandsUnlessGlobbingIsOff() {
    val range = assertIs<Range>(parse("curl -H 'Cookie: a=b' 'https://x.org/p[1-2].rar'").single())
    assertEquals(mapOf("Cookie" to "a=b"), range.headers)
    assertEquals(range.urls.map { Link(it, range.headers) }, range.links)

    assertEquals(
      listOf(Link("https://x.org/p[1-2].rar")),
      parse("curl -g 'https://x.org/p[1-2].rar'"),
    )
  }

  @Test
  fun parseIntake_malformedCurl_fallsBackToTheLinksInIt() {
    val cases = mapOf(
      "curl 'https://example.com/a.zip -H 'Cookie: a=b" to "https://example.com/a.zip",
      "curl https://example.com/a.zip -H" to "https://example.com/a.zip",
      "curl \"https://example.com/a.zip\nhttps://example.com/b.zip" to "https://example.com/a.zip",
    )
    cases.forEach { (text, url) ->
      val items = parse(text)
      assertEquals(Link(url), items.first(), text)
      assertTrue(items.links().all { it.headers.isEmpty() }, text)
    }
  }

  @Test
  fun parseIntake_unclosedCurlQuote_readsTheLinesAfterIt() {
    val text = "curl 'https://example.com/a.zip\nhttps://example.com/b.zip"

    assertEquals(listOf("https://example.com/a.zip", "https://example.com/b.zip"), urls(text))
  }

  @Test
  fun parseIntake_curlQuoteNeverClosed_readsLaterCommandsOnTheirOwn() {
    val notes = List(300) { "note $it" }.joinToString("\n")
    val text = "curl 'https://example.com/a.zip\n$notes\ncurl https://example.com/b.zip -b a=b"

    assertEquals(
      listOf(
        Link("https://example.com/a.zip"),
        Link("https://example.com/b.zip", mapOf("Cookie" to "a=b")),
      ),
      parse(text),
    )
  }

  @Test
  fun isLinkList_textAndCsvFiles_areLists() {
    assertTrue(LinkParser.isLinkList("links.txt"))
    assertTrue(LinkParser.isLinkList("EXPORT.CSV"))
    assertFalse(LinkParser.isLinkList("ubuntu.torrent"))
    assertFalse(LinkParser.isLinkList("notes.txt.zip"))
  }

  @Test
  fun quickAddLink_onePlainLink_returnsIt() {
    listOf("https://example.com/a.iso", "http://example.com/a", "ftp://example.com/a.bin")
      .forEach { assertEquals(Link(it), LinkParser.quickAddLink(" $it\n"), it) }
    assertEquals(Link("https://example.com/a.iso"), LinkParser.quickAddLink("example.com/a.iso"))
  }

  @Test
  fun quickAddLink_anythingElse_opensTheSheet() {
    listOf(
      "https://a.org/1.zip https://b.org/2.zip",
      "magnet:?xt=urn:btih:$HEX_HASH",
      "https://example.com/a.torrent?dl=1",
      "curl -H 'Cookie: a=b' https://example.com/a.zip",
      "curl https://example.com/a.zip",
      "curl https://example.com/a.zip -H",
      "https://x.org/p[1-2].rar",
      "ubuntu iso",
      "ed2k://|file|a|1|0123|/",
      "",
    ).forEach { assertNull(LinkParser.quickAddLink(it), it) }
  }

  @Test
  fun linkKindOf_scheme_picksTheKind() {
    val cases = mapOf(
      "https://x.org/a.iso" to LinkKind.Http,
      "HTTP://x.org/a" to LinkKind.Http,
      "https://x.org/a.TORRENT?x=1#y" to LinkKind.TorrentFile,
      "https://x.org/a.torrent.zip" to LinkKind.Http,
      "ftp://x.org/a.torrent" to LinkKind.Ftp,
      "ftps://x.org/a" to LinkKind.Ftp,
      "Magnet:?xt=urn:btih:$HEX_HASH" to LinkKind.Magnet,
      "torrent:$HEX_HASH" to LinkKind.Other,
    )
    cases.forEach { (url, kind) -> assertEquals(kind, LinkKind.of(url), url) }
  }

  private companion object {
    const val HEX_HASH = "3f2a91c0d4e5f60718293a4b5c6d7e8f90a1b2c3"
    const val BASE32_HASH = "H4VJDQGU4X3AOGBJHJFVY3L6R6IKDMWD"
  }
}
