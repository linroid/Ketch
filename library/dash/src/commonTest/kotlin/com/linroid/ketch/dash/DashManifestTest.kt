package com.linroid.ketch.dash

import com.linroid.ketch.api.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashManifestTest {
  private val base = "https://example.com/media/index.m3u8?signature=a%2Fb"

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
  fun dash_simpleTimeTemplate_offsetsUrlsAndUsesDeltaForCoverage() {
    val dollar = '$'
    fun manifest(delta: String = "", offset: String = "100") = """
      <MPD mediaPresentationDuration="PT4S"><Period><AdaptationSet mimeType="video/mp4">
        <Representation id="v"><SegmentTemplate timescale="10" duration="20"
          presentationTimeOffset="$offset" $delta
          initialization="init.mp4" media="${dollar}Time${dollar}.m4s"/>
        </Representation>
      </AdaptationSet></Period></MPD>
    """.trimIndent()
    fun urls(delta: String = "") = parseDash(manifest(delta), base).parts.drop(1)
      .map { it.url.substringAfterLast('/') }
    assertEquals(listOf("100.m4s", "120.m4s"), urls())
    assertEquals(listOf("100.m4s", "120.m4s", "140.m4s"), urls("eptDelta='-5'"))
    assertEquals(listOf("100.m4s"), urls("eptDelta='20'"))
    for (delta in listOf("invalid", "-101", Long.MAX_VALUE.toString())) {
      assertFailsWith<KetchError.SourceError> { urls("eptDelta='$delta'") }
    }
    assertFailsWith<KetchError.SourceError> {
      parseDash(manifest(offset = (Long.MAX_VALUE - 10).toString()), base)
    }
  }

  @Test
  fun dash_singleFileSegmentList_usesBaseForOmittedUrls() {
    val plan = parseDash("""
      <MPD><BaseURL>../files/</BaseURL><Period><AdaptationSet mimeType="video/mp4">
        <Representation id="v"><BaseURL>clip.mp4?token=a%2Fb</BaseURL><SegmentList>
          <Initialization range="0-3"/>
          <SegmentURL mediaRange="4-9"/>
        </SegmentList></Representation>
      </AdaptationSet></Period></MPD>
    """.trimIndent(), base)
    assertEquals(listOf(0L..3L, 4L..9L), plan.parts.map { it.range })
    assertEquals(listOf("https://example.com/files/clip.mp4?token=a%2Fb"),
      plan.parts.map { it.url }.distinct())
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

}
