package com.linroid.ketch.core.media

import com.linroid.ketch.core.media.Mp4Fixtures.init
import com.linroid.ketch.core.media.Mp4Fixtures.segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Mp4TracksTest {
  @Test
  fun mergeInitSegments_numbersTracksAndRescalesAudio() {
    val merged = mergeInitSegments(
      init(trackId = 7, timescale = 1000, duration = 4000, editDuration = 4000),
      init(trackId = 1, timescale = 600, duration = 1200, editDuration = 600),
    )
    val top = mp4Boxes(merged)
    assertEquals(listOf("ftyp", "moov"), top.map { it.type })
    val moov = mp4Boxes(merged, top[1].payload, top[1].end)
    assertEquals(listOf("mvhd", "trak", "trak", "mvex"), moov.map { it.type })
    assertEquals(3L, u32(merged, moov[0].end - 4))
    val traks = moov.filter { it.type == "trak" }.map { trak ->
      mp4Boxes(merged, trak.payload, trak.end)
    }
    val tkhds = traks.map { children -> children.first { it.type == "tkhd" } }
    assertEquals(listOf(1L, 2L), tkhds.map { u32(merged, it.payload + 12) })
    // The audio track's 1200 ticks of 1/600 s are 2000 of the video's 1/1000 s.
    assertEquals(listOf(4000L, 2000L), tkhds.map { u32(merged, it.payload + 20) })
    val elst = traks.map { children ->
      val edts = children.first { it.type == "edts" }
      mp4Boxes(merged, edts.payload, edts.end).single()
    }
    assertEquals(listOf(4000L, 1000L), elst.map { u32(merged, it.payload + 8) })
    val trex = mp4Boxes(merged, moov[3].payload, moov[3].end)
    assertEquals(listOf(1L, 2L), trex.map { u32(merged, it.payload + 4) })
  }

  @Test
  fun mergeInitSegments_unfragmentedOrSeveralTracks_areRefused() {
    val video = init(1, 1000, 0, 0)
    // Without its movie extends box, the movie is not fragmented.
    val unfragmented = video.copyOf().also { bytes ->
      val at = (0..bytes.size - 4)
        .first { bytes.copyOfRange(it, it + 4).decodeToString() == "mvex" }
      "skip".encodeToByteArray().copyInto(bytes, at)
    }
    assertFailsWith<IllegalArgumentException> { mergeInitSegments(unfragmented, video) }
    assertFailsWith<IllegalArgumentException> {
      mergeInitSegments(segment(1, 1, "data"), video)
    }
  }

  @Test
  fun fragmentRewriter_anyChunks_keepsFragmentsWithNewIdsAndSequences() {
    val input = segment(trackId = 9, sequence = 42, payload = "samples")
    for (chunk in listOf(1, 3, 8, input.size)) {
      var sequence = 4L
      val rewriter = FragmentRewriter(2, { ++sequence }, sourceStart = 0, outputStart = 1000)
      val out = input.toList().chunked(chunk)
        .flatMap { rewriter.update(it.toByteArray()).toList() }.toByteArray()
      rewriter.finish()
      val boxes = mp4Boxes(out)
      assertEquals(listOf("moof", "mdat"), boxes.map { it.type })
      val moof = mp4Boxes(out, boxes[0].payload, boxes[0].end)
      assertEquals(5L, u32(out, moof[0].payload + 4))
      val tfhd = mp4Boxes(out, moof[1].payload, moof[1].end).first()
      assertEquals(2L, u32(out, tfhd.payload + 4))
      assertEquals("samples", out.copyOfRange(boxes[1].payload, boxes[1].end).decodeToString())
    }
  }

  @Test
  fun fragmentRewriter_boxBetweenFragmentAndData_isKeptForSampleOffsets() {
    val free = Mp4Fixtures.box("free", ByteArray(5))
    val input = segment(trackId = 1, sequence = 1, payload = "samples", beforeData = free)
    val rewriter = FragmentRewriter(1, { 1 }, sourceStart = 0, outputStart = 0)
    val out = rewriter.update(input)
    rewriter.finish()
    assertEquals(listOf("moof", "free", "mdat"), mp4Boxes(out).map { it.type })
  }

  @Test
  fun fragmentRewriter_absoluteBaseOffset_pointsIntoTheOutput() {
    // The fragment's media lies 100 bytes into a file of which the segment starts at 5000.
    val plain = segment(trackId = 1, sequence = 1, payload = "x", baseDataOffset = 0)
    val moofAt = mp4Boxes(plain).first { it.type == "moof" }.start
    val input = segment(trackId = 1, sequence = 1, payload = "x",
      baseDataOffset = 5000L + moofAt + 100)
    val rewriter = FragmentRewriter(1, { 1 }, sourceStart = 5000, outputStart = 300)
    val out = rewriter.update(input)
    rewriter.finish()
    val moof = mp4Boxes(out).first()
    val traf = mp4Boxes(out, moof.payload, moof.end).last()
    val tfhd = mp4Boxes(out, traf.payload, traf.end).first()
    assertEquals(300L + 100, (u32(out, tfhd.payload + 8) shl 32) or u32(out, tfhd.payload + 12))
  }

  @Test
  fun fragmentRewriter_truncatedOrEmpty_isRefused() {
    val input = segment(1, 1, "samples")
    val truncated = FragmentRewriter(1, { 1 }, 0, 0)
    truncated.update(input.copyOf(input.size - 2))
    assertFailsWith<IllegalArgumentException> { truncated.finish() }
    val empty = FragmentRewriter(1, { 1 }, 0, 0)
    empty.update(Mp4Fixtures.box("styp", ByteArray(4)))
    assertFailsWith<IllegalArgumentException> { empty.finish() }
  }

  @Test
  fun mergeTracks_interleavesByStartTime() {
    fun plan(prefix: String, starts: List<Double>) = MediaPlan(
      listOf(MediaPart("https://a/$prefix-init", init = true)) +
        starts.map { MediaPart("https://a/$prefix-${it.toInt()}", startSeconds = it) },
      "mp4",
    )
    val merged = mergeTracks(
      plan("v", listOf(0.0, 4.0, 8.0)),
      plan("a", listOf(0.0, 2.0, 4.0, 6.0)),
    )
    assertEquals(2, merged.tracks)
    assertEquals(
      listOf("v-init", "a-init", "v-0", "a-0", "a-2", "v-4", "a-4", "a-6", "v-8"),
      merged.parts.map { it.url.substringAfterLast('/') },
    )
    assertEquals(listOf(0, 1, 0, 1, 1, 0, 1, 1, 0), merged.parts.map { it.track })
    assertFailsWith<IllegalArgumentException> {
      mergeTracks(MediaPlan(listOf(MediaPart("https://a/1.ts")), "ts"), plan("a", listOf(0.0)))
    }
  }

  @Test
  fun mediaVariantIds_followTheKeysNotTheirOrder() {
    val ids = mediaVariantIds(listOf("a|1", "b|2", "a|1"))
    assertEquals(ids[0] + "-2", ids[2])
    assertEquals(mediaVariantIds(listOf("b|2", "a|1")), listOf(ids[1], ids[0]))
  }

  @Test
  fun mediaVariantNames_describeHeightAndRateAndNumberRepeats() {
    assertEquals(
      listOf("1080p · 5.1 Mbps", "720p (1)", "720p (2)", "128 kbps", "Variant 5"),
      mediaVariantNames(listOf(1080, 720, 720, null, null), listOf(5_080_000, 0, 0, 128_000, 0)),
    )
  }

  private fun u32(bytes: ByteArray, at: Int): Long =
    ((bytes[at].toLong() and 0xff) shl 24) or ((bytes[at + 1].toLong() and 0xff) shl 16) or
      ((bytes[at + 2].toLong() and 0xff) shl 8) or (bytes[at + 3].toLong() and 0xff)
}
