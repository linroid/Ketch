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


  @Test
  fun dash_separateAudio_listsVideoVariantsAndMergesMainAudio() {
    val d = '$'
    val stream = parseDashStream("""
      <MPD mediaPresentationDuration="PT4S"><Period>
        <AdaptationSet contentType="video" mimeType="video/mp4">
          <SegmentTemplate timescale="1" duration="2"
            initialization="${d}RepresentationID${d}/i.mp4"
            media="${d}RepresentationID${d}/${d}Number${d}.m4s"/>
          <Representation id="low" bandwidth="800000" width="640" height="360"/>
          <Representation id="high" bandwidth="3000000" width="1920" height="1080" codecs="avc1"/>
        </AdaptationSet>
        <AdaptationSet mimeType="audio/mp4" lang="de">
          <SegmentTemplate timescale="1" duration="1" initialization="de/i.mp4"
            media="de/${d}Number${d}.m4s"/>
          <Representation id="de" bandwidth="64000"/>
        </AdaptationSet>
        <AdaptationSet mimeType="audio/mp4" lang="en">
          <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>
          <SegmentTemplate timescale="1" duration="2" initialization="en/i.mp4"
            media="en/${d}Number${d}.m4s"/>
          <Representation id="en-low" bandwidth="64000"/>
          <Representation id="en" bandwidth="128000"/>
        </AdaptationSet>
        <AdaptationSet mimeType="text/vtt"><Representation id="subs"/></AdaptationSet>
      </Period></MPD>
    """.trimIndent(), "https://example.com/index.mpd")
    assertEquals(listOf(3_000_000L, 800_000L), stream.variants.map { it.bandwidth })
    assertEquals("1920x1080", stream.variants[0].metadata["resolution"])
    val plan = stream.plan(1)
    assertEquals(2, plan.tracks)
    assertEquals(
      listOf("low/i.mp4", "en/i.mp4", "low/1.m4s", "en/1.m4s", "low/2.m4s", "en/2.m4s"),
      plan.parts.map { it.url.removePrefix("https://example.com/") })
    assertEquals(listOf(0, 1, 0, 1, 0, 1), plan.parts.map { it.track })
  }

  @Test
  fun dash_audioOnly_choosesAmongTheMainAudioRepresentations() {
    val stream = parseDashStream("""
      <MPD mediaPresentationDuration="PT2S"><Period>
        <AdaptationSet mimeType="audio/mp4">
          <SegmentTemplate duration="2" initialization="i.mp4" media="s.m4s"/>
          <Representation id="a" bandwidth="64000"/><Representation id="b" bandwidth="128000"/>
        </AdaptationSet>
      </Period></MPD>
    """.trimIndent(), "https://example.com/index.mpd")
    assertEquals(listOf(128_000L, 64_000L), stream.variants.map { it.bandwidth })
    assertEquals(1, stream.plan().tracks)
  }

  @Test
  fun dash_segmentBase_alone_downloadsTheWholeFile() {
    val stream = parseDashStream("""
      <MPD type="static" mediaPresentationDuration="PT10S"><Period>
        <AdaptationSet contentType="video" mimeType="video/mp4">
          <Representation id="v" bandwidth="100" height="360"><BaseURL>video.mp4</BaseURL>
            <SegmentBase indexRange="900-1099"><Initialization range="0-899"/></SegmentBase>
          </Representation>
        </AdaptationSet>
      </Period></MPD>
    """.trimIndent(), "https://example.com/media/index.mpd")

    assertEquals(emptyList(), stream.indexesNeeded())
    val plan = stream.plan()
    assertEquals(1, plan.tracks)
    assertEquals(listOf("https://example.com/media/video.mp4"), plan.parts.map { it.url })
    assertEquals(listOf(null), plan.parts.map { it.range })
  }

  @Test
  fun dash_segmentBase_withSeparateAudio_interleavesTheIndexedFragments() {
    val stream = parseDashStream("""
      <MPD type="static" mediaPresentationDuration="PT4S"><Period>
        <AdaptationSet contentType="video" mimeType="video/mp4">
          <Representation id="v" bandwidth="100"><BaseURL>v.mp4</BaseURL>
            <SegmentBase indexRange="100-155"><Initialization range="0-99"/></SegmentBase>
          </Representation>
        </AdaptationSet>
        <AdaptationSet contentType="audio" mimeType="audio/mp4">
          <Representation id="a" bandwidth="10"><BaseURL>a.mp4</BaseURL>
            <SegmentBase indexRange="50-93" timescale="1000" presentationTimeOffset="0"/>
          </Representation>
        </AdaptationSet>
      </Period></MPD>
    """.trimIndent(), "https://example.com/media/index.mpd")

    val needed = stream.indexesNeeded()
    assertEquals(listOf("https://example.com/media/v.mp4", "https://example.com/media/a.mp4"),
      needed.map { it.url })
    // Without the indexes, each file follows its initialization whole.
    assertEquals(listOf(0L..99L, 0L..49L, null, null), stream.plan().parts.map { it.range })

    val video = sidx(timescale = 10, references = listOf(10L to 20L, 10L to 20L))
    val audio = sidx(timescale = 10, references = listOf(4L to 40L))
    val plan = stream.plan(0, mapOf(needed[0] to video, needed[1] to audio))
    assertEquals(2, plan.tracks)
    assertEquals(
      listOf(0 to 0L..99L, 1 to 0L..49L, 0 to 156L..165L, 1 to 94L..97L, 0 to 166L..175L),
      plan.parts.map { it.track to it.range },
    )
  }

  @Test
  fun dash_segmentBase_withoutBaseUrlOrRanges_isRefusedWhereItCannotWork() {
    assertFailsWith<KetchError.SourceError> {
      parseDash("""
        <MPD type="static"><Period><AdaptationSet mimeType="video/mp4">
          <Representation id="v"><SegmentBase indexRange="0-9"/></Representation>
        </AdaptationSet></Period></MPD>
      """.trimIndent(), base)
    }
    val stream = parseDashStream("""
      <MPD type="static"><Period>
        <AdaptationSet contentType="video" mimeType="video/mp4">
          <Representation id="v"><BaseURL>v.mp4</BaseURL></Representation>
        </AdaptationSet>
        <AdaptationSet contentType="audio" mimeType="audio/mp4">
          <Representation id="a"><BaseURL>a.mp4</BaseURL></Representation>
        </AdaptationSet>
      </Period></MPD>
    """.trimIndent(), "https://example.com/media/index.mpd")
    // Merging needs each file's initialization apart from its fragments.
    assertFailsWith<KetchError.SourceError> { stream.plan() }
  }

  @Test
  fun dash_segmentBase_inheritsWhatARepresentationLeavesOut() {
    val stream = parseDashStream("""
      <MPD type="static"><Period>
        <AdaptationSet contentType="video" mimeType="video/mp4">
          <SegmentBase timescale="1000" presentationTimeOffset="2000" indexRange="1-2">
            <Initialization range="0-99"/>
          </SegmentBase>
          <Representation id="v" bandwidth="100"><BaseURL>v.mp4</BaseURL>
            <SegmentBase indexRange="100-155"/>
          </Representation>
        </AdaptationSet>
      </Period></MPD>
    """.trimIndent(), "https://example.com/media/index.mpd")

    val track = stream.variants.single().track as DashTrack.SingleFile
    assertEquals(0L..99L, track.initRange)
    assertEquals(100L..155L, track.indexRange)
    assertEquals(2.0, track.timeOffsetSeconds)
  }

  @Test
  fun dash_otherContainers_areLeftOutBesideMp4() {
    val stream = parseDashStream("""
      <MPD type="static"><Period>
        <AdaptationSet contentType="audio" mimeType="audio/webm">
          <Representation id="opus" bandwidth="64000"><BaseURL>a.webm</BaseURL></Representation>
        </AdaptationSet>
        <AdaptationSet contentType="video">
          <Representation id="h264" bandwidth="500" mimeType="video/mp4">
            <BaseURL>v.mp4</BaseURL>
            <SegmentBase indexRange="10-19"><Initialization range="0-9"/></SegmentBase>
          </Representation>
        </AdaptationSet>
        <AdaptationSet contentType="video" mimeType="video/webm">
          <Representation id="vp9" bandwidth="900"><BaseURL>v.webm</BaseURL></Representation>
        </AdaptationSet>
        <AdaptationSet contentType="audio" mimeType="audio/mp4">
          <Representation id="aac" bandwidth="128000"><BaseURL>a.mp4</BaseURL>
            <SegmentBase indexRange="10-19"><Initialization range="0-9"/></SegmentBase>
          </Representation>
        </AdaptationSet>
      </Period></MPD>
    """.trimIndent(), "https://example.com/media/index.mpd")

    assertEquals(listOf("null|h264|500|null|null"), stream.variants.map { it.key })
    assertEquals(listOf("https://example.com/media/v.mp4", "https://example.com/media/a.mp4"),
      stream.indexesNeeded().map { it.url })
    assertFailsWith<KetchError.SourceError> {
      parseDash("""
        <MPD type="static"><Period><AdaptationSet mimeType="video/webm">
          <Representation id="vp9"><BaseURL>v.webm</BaseURL></Representation>
        </AdaptationSet></Period></MPD>
      """.trimIndent(), base)
    }
  }

  /** A version 0 `sidx` box whose fragments start right after it, from time 0. */
  private fun sidx(timescale: Long, references: List<Pair<Long, Long>>): ByteArray {
    val body = mutableListOf<Byte>()
    fun u32(value: Long) = (3 downTo 0).forEach { body += (value ushr (it * 8)).toByte() }
    listOf(0L, 1L, timescale, 0L, 0L, references.size.toLong()).forEach(::u32)
    for ((size, duration) in references) listOf(size, duration, 0x90000000).forEach(::u32)
    val total = body.size + 8
    return byteArrayOf((total ushr 24).toByte(), (total ushr 16).toByte(),
      (total ushr 8).toByte(), total.toByte()) + "sidx".encodeToByteArray() + body.toByteArray()
  }
}
