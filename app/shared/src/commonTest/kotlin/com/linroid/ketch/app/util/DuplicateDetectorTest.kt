package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuplicateDetectorTest {
  private data class Entry(val id: String, val request: DownloadRequest)

  private fun entry(id: String, url: String, resolved: ResolvedSource? = null) =
    Entry(id, DownloadRequest(url, resolvedSource = resolved))

  private fun detector(vararg entries: Entry) = DuplicateDetector(entries.toList()) { it.request }

  @Test
  fun normalizeUrl_sameFile_comparesEqual() {
    listOf(
      "HTTPS://Example.COM:443/a/b?x=1#frag" to "https://example.com/a/b?x=1",
      "https://example.com" to "https://example.com/",
      "https://example.com?q=1" to "https://example.com/?q=1",
      "http://example.com:80/a" to "http://example.com/a",
      "ftp://user:secret@host.org:21/f" to "ftp://host.org/f",
      "ftp://user:p#ss@host.org/f" to "ftp://host.org/f",
      "  https://x.org/a  " to "https://x.org/a",
      "https://x.org/a%7Eb%2dc" to "https://x.org/a~b-c",
      "https://x.org/a%2fb" to "https://x.org/a%2Fb",
      "https://x.org/a b" to "https://x.org/a%20b",
      "https://x.org/ünï" to "https://x.org/%C3%BCn%C3%AF",
      "https://x.org/%c3%bc" to "https://x.org/%C3%BC",
      "https://x.org/😀" to "https://x.org/%F0%9F%98%80",
      "https://x.org/100%" to "https://x.org/100%25",
      "https://x.org/a%zzb%4" to "https://x.org/a%25zzb%254",
      "https://x.org/a{b}|c" to "https://x.org/a%7Bb%7D%7Cc",
    ).forEach { (a, b) -> assertEquals(normalizeUrl(b), normalizeUrl(a), "$a vs $b") }
  }

  @Test
  fun normalizeUrl_differentFile_comparesDifferent() {
    listOf(
      "https://x.org/a%2Fb" to "https://x.org/a/b",
      "https://x.org/a%3Fb" to "https://x.org/a?b",
      "https://x.org/a?b=1" to "https://x.org/a?b=2",
      "https://x.org/a?b=1&c=2" to "https://x.org/a?c=2&b=1",
      "http://x.org/a" to "https://x.org/a",
      "https://x.org/a" to "https://x.org/a/",
      "https://x.org/A" to "https://x.org/a",
      "https://x.org:8443/a" to "https://x.org/a",
    ).forEach { (a, b) -> assertNotEquals(normalizeUrl(b), normalizeUrl(a), "$a vs $b") }
  }

  @Test
  fun duplicateKeys_torrentLinks_useTheInfoHash() {
    val key = setOf("btih:$HEX_HASH")
    listOf(
      "magnet:?xt=urn:btih:$HEX_HASH",
      "magnet:?xt=urn:btih:${HEX_HASH.uppercase()}&dn=Big&tr=udp%3A%2F%2Ft.org%3A1337",
      "magnet:?dn=Big&xt=urn:btih:$BASE32_HASH",
      "magnet:?xt=urn:btih:${BASE32_HASH.lowercase()}",
      "MAGNET:?XT=urn%3Abtih%3A$HEX_HASH",
      "torrent:$HEX_HASH",
    ).forEach { assertEquals(key, duplicateKeys(it), it) }
  }

  @Test
  fun duplicateKeys_hybridMagnet_hasBothHashes() {
    val v2 = "a".repeat(64)

    assertEquals(
      setOf("btih:$HEX_HASH", "btmh:$v2"),
      duplicateKeys("magnet:?xt=urn:btih:$HEX_HASH&xt=urn:btmh:1220$v2"),
    )
    assertEquals(setOf("btmh:$v2"), duplicateKeys("torrent:${v2.uppercase()}"))
  }

  @Test
  fun duplicateKeys_magnetWithoutAKnownHash_usesTheLink() {
    val magnet = "magnet:?xt=urn:sha1:ABC&dn=x"

    assertEquals(setOf("url:" + normalizeUrl(magnet)), duplicateKeys(magnet))
    assertEquals(1, duplicateKeys("magnet:?xt=urn:btih:tooshort").size)
  }

  @Test
  fun duplicateKeys_resolvedSource_addsItsUrlAndInfoHash() {
    val resolved = ResolvedSource(
      url = "torrent:$HEX_HASH",
      sourceType = "torrent",
      totalBytes = 1,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
      metadata = mapOf("infoHash" to HEX_HASH.uppercase()),
    )

    assertEquals(
      setOf("url:https://x.org/a.torrent", "btih:$HEX_HASH"),
      duplicateKeys("https://x.org/a.torrent", resolved),
    )
  }

  @Test
  fun find_sameUrl_returnsTheTask() {
    val existing = entry("1", "https://example.com/ubuntu.iso")
    val detector = detector(entry("0", "https://example.com/other.iso"), existing)

    assertEquals(existing, detector.find("HTTPS://EXAMPLE.COM/ubuntu.iso#download"))
    assertNull(detector.find("https://example.com/ubuntu.iso?mirror=2"))
  }

  @Test
  fun find_magnetOfATorrentFromAFile_matchesByInfoHash() {
    val fromFile = entry("1", "torrent:$HEX_HASH")
    val detector = detector(fromFile)

    assertEquals(fromFile, detector.find("magnet:?xt=urn:btih:$BASE32_HASH&dn=Big"))
  }

  @Test
  fun find_taskResolvedFromATorrentLink_matchesItsInfoHash() {
    val resolved = ResolvedSource(
      url = "https://x.org/a.torrent",
      sourceType = "torrent",
      totalBytes = 1,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
      metadata = mapOf("infoHash" to HEX_HASH),
    )
    val fromLink = entry("1", "https://x.org/a.torrent", resolved)
    val fromMagnet = entry("2", "magnet:?xt=urn:btih:$HEX_HASH")

    assertEquals(fromLink, detector(fromLink).find("magnet:?xt=urn:btih:$HEX_HASH"))
    assertEquals(fromMagnet, detector(fromMagnet).find("https://other.org/b.torrent", resolved))
    assertNull(detector(fromMagnet).find("https://other.org/b.torrent"))
  }

  @Test
  fun find_severalMatches_returnsTheLast() {
    val older = entry("1", "https://x.org/a.iso")
    val newer = entry("2", "https://x.org/a.iso#again")

    assertEquals(newer, detector(older, newer).find("https://x.org/a.iso"))
  }

  @Test
  fun find_noTasks_returnsNull() {
    assertNull(detector().find("https://x.org/a.iso"))
    assertTrue(duplicateKeys("https://x.org/a.iso").single().startsWith("url:"))
  }

  private companion object {
    const val HEX_HASH = "3f2a91c0d4e5f60718293a4b5c6d7e8f90a1b2c3"
    const val BASE32_HASH = "H4VJDQGU4X3AOGBJHJFVY3L6R6IKDMWD"
  }
}
