package com.linroid.ketch.metalink

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetalinkTest {
  private val sha256 = "a".repeat(64)
  private val sha1 = "b".repeat(40)

  @Test
  fun parse_metalink4_readsFileHashesPiecesAndRankedMirrors() {
    val metalink = parseMetalink(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <!-- generated -->
      <metalink xmlns="urn:ietf:params:xml:ns:metalink">
        <published>2026-01-01T00:00:00Z</published>
        <file name="images/ubuntu.iso">
          <size>1000</size>
          <hash type="md5">${"c".repeat(32)}</hash>
          <hash type="sha-256">$sha256</hash>
          <hash type="sha-384">ignored</hash>
          <pieces length="512" type="sha-1">
            <hash>$sha1</hash>
            <hash>${"d".repeat(40)}</hash>
          </pieces>
          <url location="DE" priority="2">https://de.example/ubuntu.iso</url>
          <url priority="1">https://us.example/ubuntu.iso?a=1&amp;b=2</url>
          <url priority="3">ftp://ftp.example/ubuntu.iso</url>
          <url>https://user:pass@private.example/ubuntu.iso</url>
          <url priority="2">https://de.example/ubuntu.iso</url>
          <metaurl mediatype="torrent">https://example/ubuntu.torrent</metaurl>
        </file>
      </metalink>
      """.trimIndent(),
      secure = true,
    )

    val file = metalink.files.single()
    assertEquals("ubuntu.iso", file.name)
    assertEquals(1000, file.size)
    assertEquals(Checksum(ChecksumAlgorithm.SHA_256, sha256), file.checksum)
    assertEquals(setOf(ChecksumAlgorithm.MD5, ChecksumAlgorithm.SHA_256),
      file.hashes.map { it.algorithm }.toSet())
    assertEquals(MetalinkPieces(ChecksumAlgorithm.SHA_1, 512, listOf(sha1, "d".repeat(40))),
      file.pieces)
    assertEquals(
      listOf(
        MetalinkUrl("https://us.example/ubuntu.iso?a=1&b=2", 1),
        MetalinkUrl("https://de.example/ubuntu.iso", 2, "de"),
      ),
      file.urls,
    )
  }

  @Test
  fun parse_metalink3_readsPreferencesAndNumberedPieces() {
    val metalink = parseMetalink(
      """
      <metalink version="3.0" xmlns="http://www.metalinker.org/">
        <files>
          <file name="app.tar.gz">
            <size>700</size>
            <verification>
              <hash type="sha256">$sha256</hash>
              <pieces type="sha1" length="512">
                <hash piece="1">${"d".repeat(40)}</hash>
                <hash piece="0">$sha1</hash>
              </pieces>
            </verification>
            <resources>
              <url type="http" preference="10">http://slow.example/app.tar.gz</url>
              <url type="http" preference="90">http://fast.example/app.tar.gz</url>
              <url type="bittorrent">http://example/app.torrent.no</url>
            </resources>
          </file>
        </files>
      </metalink>
      """.trimIndent(),
      secure = false,
    )

    val file = metalink.files.single()
    assertEquals(700, file.size)
    assertEquals(listOf(sha1, "d".repeat(40)), file.pieces?.hashes)
    assertEquals(
      listOf("http://fast.example/app.tar.gz", "http://slow.example/app.tar.gz"),
      file.urls.map { it.url },
    )
    assertEquals(listOf(10, 90), file.urls.map { it.priority })
  }

  @Test
  fun parse_overHttpsWithoutStrongHash_keepsHttpsMirrorsOnly() {
    fun document(hash: String) = """
      <metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="f">$hash
        <url>http://plain.example/f</url><url>https://tls.example/f</url>
      </file></metalink>
    """
    val md5 = "<hash type=\"md5\">${"c".repeat(32)}</hash>"
    assertEquals(listOf("https://tls.example/f"),
      parseMetalink(document(md5), secure = true).files.single().urls.map { it.url })
    assertEquals(2, parseMetalink(document(md5), secure = false).files.single().urls.size)
    val strong = "<hash type=\"sha-256\">$sha256</hash>"
    assertEquals(2, parseMetalink(document(strong), secure = true).files.single().urls.size)
  }

  @Test
  fun parse_invalidPieces_areIgnored() {
    val gap = """
      <metalink><files><file name="f"><verification>
        <pieces type="sha1" length="512">
          <hash piece="0">$sha1</hash><hash piece="2">$sha1</hash>
        </pieces>
      </verification><resources><url>https://a.example/f</url></resources></file></files></metalink>
    """
    assertNull(parseMetalink(gap, secure = false).files.single().pieces)
    val badHash = """
      <metalink><file name="f"><pieces type="sha-1" length="512"><hash>xyz</hash></pieces>
      <url>https://a.example/f</url></file></metalink>
    """
    assertNull(parseMetalink(badHash, secure = false).files.single().pieces)
  }

  @Test
  fun parse_skipsFilesWithoutMirrorsOrName() {
    val metalink = parseMetalink(
      """
      <metalink>
        <file name=".."><url>https://a.example/x</url></file>
        <file name="ftp-only"><url>ftp://a.example/x</url></file>
        <file name="kept"><url>https://a.example/kept</url></file>
      </metalink>
      """,
      secure = false,
    )
    assertEquals(listOf("kept"), metalink.files.map { it.name })
  }

  @Test
  fun parse_refusesWhatIsNotAUsableMetalink() {
    for (text in listOf(
      "<html><body>nope</body></html>",
      "<metalink><file name=\"f\"><url>ftp://a/f</url></file></metalink>",
      "<!DOCTYPE metalink [<!ENTITY x \"y\">]><metalink/>",
      "<metalink><file name=\"f\">",
      "not xml",
    )) {
      assertFailsWith<MetalinkException>(text) { parseMetalink(text, secure = false) }
    }
  }

  @Test
  fun mirrorUrl_acceptsHttpOnlyWithoutCredentials() {
    assertEquals("https://a.example/f?x=1", mirrorUrl("  HTTPS://a.example/f?x=1 "))
    assertNull(mirrorUrl("https://a.example/bad path"))
    assertNull(mirrorUrl("https://u:p@a.example/f"))
    assertNull(mirrorUrl("file:///etc/passwd"))
    assertNull(mirrorUrl("https://a.example/" + "x".repeat(9000)))
  }

  @Test
  fun parseXml_readsEntitiesCdataAndPrefixes() {
    val root = parseXml(
      "﻿<?xml version=\"1.0\"?><m:root xmlns:m=\"x\" a='1 &lt; 2'>" +
        "<m:child>&#72;&#x69; &amp; <![CDATA[<raw>]]><!-- c --></m:child><empty/></m:root>",
    )
    assertEquals("root", root.name)
    assertEquals("1 < 2", root.attributes["a"])
    assertEquals("Hi & <raw>", root.child("child")?.text)
    assertTrue(root.child("empty")!!.children.isEmpty())
  }

  @Test
  fun parseXml_refusesMalformedDocuments() {
    for (text in listOf(
      "<a></b>",
      "<a>&unknown;</a>",
      "<a b=c/>",
      "<a/><b/>",
      "<a>" + "<b>".repeat(40) + "</b>".repeat(40) + "</a>",
    )) {
      assertFailsWith<XmlException>(text) { parseXml(text) }
    }
  }
}
