package com.linroid.ketch.hls

import com.linroid.ketch.api.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsPlaylistTest {
  private val base = "https://example.com/media/index.m3u8?signature=a%2Fb"

  @Test
  fun hls_byteRangesAndInitialization_keepOrder() {
    val plan = parseHls("""
      #EXTM3U
      #EXT-X-MAP:URI="all.mp4",BYTERANGE="4@0"
      #EXTINF:1,
      #EXT-X-BYTERANGE:3@4
      all.mp4
      #EXTINF:1,
      #EXT-X-BYTERANGE:2
      all.mp4
      #EXT-X-ENDLIST
    """.trimIndent(), base).plan!!
    assertEquals(listOf(0L..3L, 4L..6L, 7L..8L), plan.parts.map { it.range })
    assertEquals("mp4", plan.extension)
  }

  @Test
  fun hls_relativeVariant_keepsItsOwnSignature() {
    val playlist = parseHls("""
      #EXTM3U
      #EXT-X-STREAM-INF:BANDWIDTH=100,CODECS="avc1.4d401f,mp4a.40.2"
      ../high/index.m3u8?signature=b%2Fc
    """.trimIndent(), base)
    assertEquals("https://example.com/high/index.m3u8?signature=b%2Fc",
      playlist.variants.single().url)
  }

  @Test
  fun hls_unsupportedPlaylists_areRejected() {
    val media = "#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST"
    for (text in listOf(
      media.replace("#EXT-X-ENDLIST", ""),
      media.replace("#EXTINF", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF"),
      media.replace("#EXTINF", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\"," +
        "KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF"),
      media.replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x12G4\n#EXTINF"),
      media.replace("#EXTINF",
        "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x${"1".repeat(33)}\n#EXTINF"),
      media.replace("#EXTINF", "#EXT-X-DISCONTINUITY\n#EXTINF"),
      media.replace("a.ts", "file:///etc/passwd"),
      media.replace("a.ts", "http://example.com/a.ts"),
      media.replace("a.ts", "#EXT-X-BYTERANGE:10\na.ts"),
      "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"",
    )) assertFailsWith<KetchError.SourceError> { parseHls(text, base) }
  }

  @Test
  fun hls_aes128Keys_useSequenceNumbersOrTheGivenIv() {
    val plan = parseHls("""
      #EXTM3U
      #EXT-X-MEDIA-SEQUENCE:7
      #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k1"
      #EXTINF:2,
      a.ts
      #EXTINF:2,
      b.ts
      #EXT-X-KEY:METHOD=AES-128,URI="k2",IV=0x000102030405060708090A0B0C0D0E0F
      #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://k2",KEYFORMAT="com.apple.streamingkeydelivery"
      #EXTINF:2,
      c.ts
      #EXT-X-KEY:METHOD=NONE
      #EXTINF:2,
      d.ts
      #EXT-X-ENDLIST
    """.trimIndent(), base).plan!!
    assertEquals(listOf("https://keys.example/k1", "https://keys.example/k1",
      "https://example.com/media/k2", null), plan.parts.map { it.key?.url })
    assertEquals(listOf(7, 8), plan.parts.take(2).map { it.key!!.iv.last().toInt() })
    assertEquals((0..15).toList(), plan.parts[2].key!!.iv.map { it.toInt() })
    val short = parseHls("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x1\n#EXTINF:1,\na.ts\n" +
      "#EXT-X-ENDLIST", base).plan!!
    assertEquals(List(15) { 0 } + 1, short.parts.single().key!!.iv.map { it.toInt() })
    assertEquals(listOf(0.0, 2.0, 4.0, 6.0), plan.parts.map { it.startSeconds })
  }

  @Test
  fun hls_encryptedInitialization_needsAnIv() {
    val text = """
      #EXTM3U
      #EXT-X-KEY:METHOD=AES-128,URI="k"IV
      #EXT-X-MAP:URI="init.mp4"
      #EXTINF:1,
      a.m4s
      #EXT-X-ENDLIST
    """.trimIndent()
    assertFailsWith<KetchError.SourceError> { parseHls(text.replace("IV", ""), base) }
    val plan = parseHls(text.replace("IV", ",IV=0x${"0".repeat(32)}"), base).plan!!
    assertTrue(plan.parts.first().init)
    assertEquals(listOf(true, true), plan.parts.map { it.key != null })
    // A FairPlay key offered beside the identity one is skipped.
    val both = text.replace("IV", ",IV=0x${"0".repeat(32)}\n" +
      "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"")
    assertEquals(listOf(true, true), parseHls(both, base).plan!!.parts.map { it.key != null })
  }

  @Test
  fun hls_master_choosesDefaultAudioRendition() {
    val playlist = parseHls("""
      #EXTM3U
      #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",LANGUAGE="en",URI="en.m3u8"
      #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Deutsch",DEFAULT=YES,URI="de.m3u8"
      #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="muxed",NAME="Main",DEFAULT=YES
      #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="subs.m3u8"
      #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="aac"
      720.m3u8
      #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="muxed"
      480.m3u8
    """.trimIndent(), base)
    assertEquals("https://example.com/media/de.m3u8",
      playlist.audioFor(playlist.variants[0])?.url)
    // Audio inside the variant needs no merging.
    assertNull(playlist.audioFor(playlist.variants[1]))
  }

}
