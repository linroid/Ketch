package com.linroid.ketch.core.media

import com.linroid.ketch.api.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MediaManifestTest {
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

  @Test
  fun dash_segmentList_resolvesBasesAndRanges() {
    val plan = parseDash("""
      <MPD type="static"><BaseURL>../files/</BaseURL><Period><AdaptationSet mimeType="video/mp4">
        <Representation id="v"><SegmentList>
          <Initialization sourceURL="video.mp4?token=a&amp;b=2" range="0-3"/>
          <SegmentURL media="video.mp4?token=a&amp;b=2" mediaRange="4-9"/>
        </SegmentList></Representation>
      </AdaptationSet></Period></MPD>
    """.trimIndent(), "https://example.com/media/index.mpd")
    assertEquals("https://example.com/files/video.mp4?token=a&b=2", plan.parts[0].url)
    assertEquals(listOf(0L..3L, 4L..9L), plan.parts.map { it.range })
  }

  @Test
  fun dash_durationTemplate_expandsNumberAndRepresentation() {
    val dollar = '$'
    val plan = parseDash("""
      <MPD mediaPresentationDuration="PT5S"><Period><AdaptationSet mimeType="audio/mp4">
        <SegmentTemplate timescale="10" duration="20" startNumber="7"
          initialization="${dollar}RepresentationID${dollar}-init.mp4"
          media="${dollar}RepresentationID${dollar}-${dollar}Number%03d${dollar}.m4s"/>
        <Representation id="audio" bandwidth="100"/>
      </AdaptationSet></Period></MPD>
    """.trimIndent(), "https://example.com/index.mpd")
    assertEquals(listOf("audio-init.mp4", "audio-007.m4s", "audio-008.m4s", "audio-009.m4s"),
      plan.parts.map { it.url.substringAfterLast('/') })
  }

  @Test
  fun dash_negativeRepeat_stopsAtNextExplicitTime() {
    val dollar = '$'
    val plan = parseDash("""
      <MPD><Period><AdaptationSet mimeType="video/mp4"><Representation id="v">
        <SegmentTemplate initialization="init.mp4" media="${dollar}Time${dollar}.m4s">
          <SegmentTimeline><S t="10" d="2" r="-1"/><S t="16" d="3" r="1"/></SegmentTimeline>
        </SegmentTemplate>
      </Representation></AdaptationSet></Period></MPD>
    """.trimIndent(), "https://example.com/index.mpd")
    assertEquals(listOf("init.mp4", "10.m4s", "12.m4s", "14.m4s", "16.m4s", "19.m4s"),
      plan.parts.map { it.url.substringAfterLast('/') })
  }

  @Test
  fun dash_unsafeAndUnsupportedStructures_areRejected() {
    for (text in listOf(
      "<!DOCTYPE MPD [<!ENTITY x SYSTEM 'file:///secret'>]><MPD>&x;</MPD>",
      "<MPD type='dynamic'/>",
      "<MPD><Period><AdaptationSet/><AdaptationSet/></Period></MPD>",
      "<MPD><ContentProtection/></MPD>",
      "<MPD><Period></MPD>",
      "<MPD/><MPD/>",
      "<MPD>&unknown;</MPD>",
    )) assertFailsWith<KetchError.SourceError> { parseDash(text, base) }
    assertFailsWith<KetchError.SourceError> {
      manifestXml("<a>".repeat(33) + "</a>".repeat(33))
    }
  }

  @Test
  fun mediaUrls_crossOriginReferences_cannotReceiveCredentials() {
    val headers = mapOf("Cookie" to "secret", "Authorization" to "Bearer secret",
      "Referer" to "https://example.com/private", "User-Agent" to "Ketch")
    val next = mediaUrl(base, "//cdn.example.com/a.ts?signature=1%2F2")
    assertEquals("https://cdn.example.com/a.ts?signature=1%2F2", next)
    assertEquals(mapOf("User-Agent" to "Ketch"), mediaHeaders(base, next, headers))
    assertEquals(headers, mediaHeaders(base, "https://example.com/other", headers))
    assertTrue(mediaUrl(base, "?signature=x").endsWith("index.m3u8?signature=x"))
  }
}
