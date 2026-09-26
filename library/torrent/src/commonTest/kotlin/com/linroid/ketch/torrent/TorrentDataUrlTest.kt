package com.linroid.ketch.torrent

import com.linroid.ketch.api.KetchError
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TorrentDataUrlTest {
  private val metainfo = Bencode.encode(mapOf(
    "announce" to "https://tracker.example/announce",
    "info" to mapOf("name" to "opened.bin", "length" to 0L, "piece length" to 16384L,
      "pieces" to ByteArray(0)),
  ))

  private fun dataUrl(bytes: ByteArray = metainfo) =
    "data:application/x-bittorrent;base64,${Base64.Default.encode(bytes)}"

  @Test
  fun decode_base64Payload_returnsMetainfo() {
    assertContentEquals(metainfo, decodeMetainfoDataUrl(dataUrl(), 4096))
  }

  @Test
  fun decode_extraParametersAndMissingPadding_accepted() {
    val payload = Base64.Default.encode(metainfo).trimEnd('=')
    val url = "data:Application/X-BitTorrent;name=opened.torrent;BASE64,$payload"
    assertContentEquals(metainfo, decodeMetainfoDataUrl(url, 4096))
  }

  @Test
  fun decode_percentEncodedPayload_rejected() {
    assertFailsWith<IllegalArgumentException> {
      decodeMetainfoDataUrl("data:application/x-bittorrent,d4%3Ainfo", 4096)
    }
  }

  @Test
  fun decode_payloadBeyondLimit_rejectedBeforeDecoding() {
    val url = dataUrl(ByteArray(7))
    assertContentEquals(ByteArray(6), decodeMetainfoDataUrl(dataUrl(ByteArray(6)), 6))
    val error = assertFailsWith<IllegalArgumentException> { decodeMetainfoDataUrl(url, 6) }
    assertEquals("Metainfo exceeds limit", error.message)
  }

  @Test
  fun resolve_dataUrl_usesTorrentUrlAndKeepsMetainfo() = runTest {
    val source = TorrentDownloadSource()
    try {
      val resolved = source.resolve(dataUrl(), emptyMap())
      val expected = source.resolveMetainfo(metainfo)
      assertEquals(expected, resolved)
      assertTrue(resolved.url.startsWith("torrent:"))
      assertEquals("opened.bin", resolved.suggestedFileName)
    } finally {
      source.close()
    }
  }

  @Test
  fun resolve_malformedDataUrl_wrapsAsSourceError() = runTest {
    val source = TorrentDownloadSource()
    try {
      val error = assertFailsWith<KetchError.SourceError> {
        source.resolve("data:application/x-bittorrent;base64,ZGU=", emptyMap())
      }
      assertEquals(TorrentDownloadSource.TYPE, error.sourceType)
    } finally {
      source.close()
    }
  }
}
