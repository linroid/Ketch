package com.linroid.ketch.hls

import com.linroid.ketch.api.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
      media.replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF"),
      media.replace("#EXTINF", "#EXT-X-DISCONTINUITY\n#EXTINF"),
      media.replace("a.ts", "file:///etc/passwd"),
      media.replace("a.ts", "http://example.com/a.ts"),
      media.replace("a.ts", "#EXT-X-BYTERANGE:10\na.ts"),
      "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"",
    )) assertFailsWith<KetchError.SourceError> { parseHls(text, base) }
  }

}
